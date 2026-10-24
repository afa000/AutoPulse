import { useEffect, useRef, useState } from 'react'
import { integer, object, readCareJson, time, wire, type ObjectData } from './care'
import { uuid } from './trips'

export function careError(status: number, raw: unknown) {
  const code = object(raw) ? raw.code : null
  if (code === 'PAGE_CHANGED' || code === 'INVALID_CURSOR') return 'Data changed or this page expired. Use Refresh newest to restart paging.'
  if (code === 'REVISION_CONFLICT') return 'Another change was saved first. Refresh and review the current revision before submitting again.'
  if (code === 'IDENTITY_CONFLICT') return 'This submission identity conflicts with a stored command. Refresh and review before making a new submission.'
  if (code === 'ODOMETER_HISTORY_CONFLICT') return 'This odometer conflicts with earlier or later records. Review the history and correct the value or measurement time.'
  if (status === 400) return 'The backend rejected these inputs. Check the UTC time, time zone, required fields and values; nothing was saved by this attempt.'
  if (status === 404) return 'This vehicle or record is unavailable. Check the vehicle and return to the list.'
  return 'Request failed. Check the local backend and refresh.'
}
export function useCare<T>(url: string, parse: (v: unknown) => T | null) {
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<{ url: string; data: T | null; busy: boolean; error: string | null; loadedAt: string | null; processing: string }>({ url, data: null, busy: true, error: null, loadedAt: null, processing: 'UNKNOWN' })
  useEffect(() => {
    let active = true, expired = false
    const controller = new AbortController()
    const timer = setTimeout(() => { expired = true; controller.abort(); if (active) setState(s => ({ ...s, busy: false, error: 'Request timed out. Refresh to try again.' })) }, 15_000)
    setState(s => ({ url, data: s.url === url ? s.data : null, loadedAt: s.url === url ? s.loadedAt : null, processing: s.url === url ? s.processing : 'UNKNOWN', busy: true, error: null }))
    void (async () => {
      try {
        const response = await fetch(url, { cache: 'no-store', signal: controller.signal })
        const raw = readCareJson(await response.text())
        if (!response.ok) throw Error(careError(response.status, raw))
        const data = parse(raw)
        if (data === null) throw Error('Unreadable response. No new data was accepted; refresh against the current backend.')
        const header = response.headers.get('X-Diagnostic-Processing-State')
        if (active && !expired) setState({ url, data, busy: false, error: null, loadedAt: new Date().toISOString(), processing: header && ['CURRENT', 'PENDING', 'REVIEW'].includes(header) ? header : 'UNKNOWN' })
      } catch (e) { if (active && !expired) setState(s => ({ ...s, busy: false, error: e instanceof Error ? e.message : 'Request failed.' })) }
      finally { clearTimeout(timer) }
    })()
    return () => { active = false; clearTimeout(timer); controller.abort() }
  }, [url, parse, attempt])
  return { ...state, data: state.url === url ? state.data : null, busy: state.url !== url || state.busy,
    retained: state.url === url && state.data !== null && (state.busy || state.error !== null), refresh: () => setAttempt(n => n + 1) }
}

type Command = { method: string; body: ObjectData }
/** Store before sending, never silently generate a new identity after an ambiguous response.
 * Session storage survives navigation/reload in this tab; no automatic replay on mount.
 */
export function useCommand(url: string, onSaved: () => void) {
  const key = `autopulse:pending:${url}`
  const [pending, setPending] = useState<Command | null>(() => {
    try {
      const stored = sessionStorage.getItem(key)
      if (!stored) return null
      const parsed = readCareJson(stored)
      return object(parsed) && object(parsed.body) && ['POST', 'PUT', 'PATCH'].includes(String(parsed.method)) ? parsed as Command : null
    } catch { return null }
  })
  const [busy, setBusy] = useState(false), [message, setMessage] = useState('')
  const mounted = useRef(false), sending = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  async function send(command: Command) {
    if (sending.current) return
    try { sessionStorage.setItem(key, wire(command)) }
    catch { setMessage('Cannot safely store the retry identity in this tab. Enable session storage before submitting.'); return }
    sending.current = true; setPending(command); setBusy(true); setMessage('')
    const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 15_000)
    try {
      const response = await fetch(url, { method: command.method, body: wire(command.body), headers: { 'Content-Type': 'application/json' }, signal: controller.signal })
      const raw = readCareJson(await response.text())
      if (!response.ok) {
        if ([400, 404, 409, 413, 415].includes(response.status)) { sessionStorage.removeItem(key); if (mounted.current) setPending(null) }
        throw Error(careError(response.status, raw))
      }
      if (!object(raw) || !uuid(raw.id) || integer(raw.revision) === null || !time(raw.recordedAt)) throw Error('Unreadable receipt. The submission may already be saved.')
      sessionStorage.removeItem(key)
      if (mounted.current) { setPending(null); setMessage('Saved. Refreshing the current evidence.'); onSaved() }
    } catch (e) {
      if (mounted.current) setMessage(`${e instanceof Error ? e.message : 'No confirmed response.'} If a retry is offered, use it to resend the identical submission, not a new record.`)
    } finally { clearTimeout(timer); sending.current = false; if (mounted.current) setBusy(false) }
  }
  return { pending, busy, message, submit: (method: string, body: ObjectData) => send(pending ?? { method, body: { ...body, requestId: crypto.randomUUID() } }),
    retry: () => { if (pending) void send(pending) } }
}
