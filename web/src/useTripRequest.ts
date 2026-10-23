import { useEffect, useState } from 'react'
import { isObject } from './telemetry'
import { parseAnalyticsJson } from './analytics'

type State<T> = { request: string; attempt: number; data: T | null; processing: string; generation: string | null; loadedAt: string | null; busy: boolean; error: string | null }
export class RequestFailure extends Error {}
function errorMessage(status: number, code: unknown, context: 'trip' | 'history') {
  if (code === 'PAGE_CHANGED' || code === 'INVALID_CURSOR') return context === 'history' ? 'History changed or its page expired. Refresh the trip summary to restart charts.' : 'Trip data changed or the page expired. Refresh newest trips to restart paging.'
  if (code === 'HISTORY_DENSITY_LIMIT') return 'Aggregate history is too dense. Choose fewer points per page or Raw readings; no partial bucket was returned.'
  if (code === 'PUBLICATION_PENDING') return 'Trip corrections are being published. Try refreshing shortly.'
  if (status === 404) return 'This vehicle or trip is not available. Check the identifier or return to Trips.'
  return 'Trip request failed. Check the local backend and try refreshing.'
}
/** Keyed pages own identity. The active guard also handles transports ignoring AbortSignal. */
export function useTripRequest<T>(url: string, parse: (value: unknown) => T | null, context: 'trip' | 'history' = 'trip') {
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<State<T>>({ request: url, attempt: 0, data: null, processing: 'UNKNOWN', generation: null, loadedAt: null, busy: true, error: null })
  useEffect(() => {
    let active = true, timedOut = false
    const controller = new AbortController()
    const timer = setTimeout(() => {
      timedOut = true; controller.abort()
      if (active) setState(previous => ({ ...previous, busy: false, error: 'Trip request timed out. Try refreshing.' }))
    }, 15_000)
    setState(previous => ({ ...previous, request: url, attempt, busy: true, error: null }))
    async function load() {
      try {
        const response = await fetch(url, { signal: controller.signal, cache: 'no-store' })
        const raw = parseAnalyticsJson(await response.text())
        if (!response.ok) throw new RequestFailure(errorMessage(response.status, isObject(raw) ? raw.code : null, context))
        const data = parse(raw)
        if (data === null) throw new RequestFailure(`Unreadable ${context === 'history' ? 'history' : 'trip'} response. Last-known data has not been replaced.`)
        const header = response.headers.get('X-Trip-Processing-State')
        const processing = header && ['CURRENT', 'PENDING', 'REVIEW'].includes(header) ? header : 'UNKNOWN'
        const token = response.headers.get('X-Trip-Data-Generation')
        const generation = token && /^[0-9]{1,20}$/.test(token) ? token : null
        if (active && !timedOut) setState({ request: url, attempt, data, processing, generation, loadedAt: new Date().toISOString(), busy: false, error: null })
      } catch (error) {
        if (active) setState(previous => ({ ...previous, busy: false, error: timedOut ? 'Trip request timed out. Try refreshing.'
          : error instanceof RequestFailure ? error.message : 'Trip request failed. Check the local backend and try refreshing.' }))
      } finally { clearTimeout(timer) }
    }
    void load()
    return () => { active = false; clearTimeout(timer); controller.abort() }
  }, [url, parse, attempt, context])
  const busy = state.busy || state.request !== url || state.attempt !== attempt
  return { ...state, busy, retained: state.data !== null && (busy || state.error !== null), refresh: () => setAttempt(previous => previous + 1) }
}
