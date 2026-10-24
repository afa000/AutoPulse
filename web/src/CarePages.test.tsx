import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { DiagnosticDetail, Diagnostics, Maintenance, MaintenanceDetail, RecommendationDetail, Recommendations } from './CarePages'
import { integer, page, readCareJson, revision, wire } from './care'
import examples from '../../contracts/examples/diagnostic-api-examples.json'
import { useCommand } from './useCare'

const id = '20000000-0000-4000-8000-000000000001'
const base = () => ({ ...structuredClone(examples.eventDetail), mil: { outcome: 'COMPLETE', illuminated: false } })
const diagnosticPage = () => ({ items: [base()], nextCursor: null, generation: '1', processingState: 'CURRENT', latestSnapshot: null })
const assessment = { state: 'UNKNOWN', reason: 'MISSING_SERVICE_HISTORY', source: 'MANUAL', ruleVersion: 'test-v1', evaluatedAt: '2026-10-23T12:00:00Z' }
const profile = () => ({ revision: 0, profile: null, oilAssessment: assessment })
const empty = () => ({ items: [], generation: '0', nextCursor: null, processingState: 'PENDING', oilAssessment: assessment })
const entry = () => ({ entryId: id, revision: 1, kind: 'ODOMETER', serviceType: null, observedAt: '2026-10-22T12:00:00Z', odometerValue: '18000.125', odometerUnit: 'MI', notes: null, correctionReason: null })
const rec = (changes = {}) => ({ id, revision: 1, kind: 'DIAGNOSTIC', state: 'OPEN', generatedAt: '2026-10-22T12:00:00Z', ruleVersion: 'confirmed-diagnostic-v1', evidence: { source: 'SIMULATED', code: 'P1999', explanation: 'Observation, not proof of repair.' }, currentObservation: { source: 'SIMULATED', capturedAt: '2026-10-22T12:00:00Z', freshness: 'STALE' }, ...changes })
const response = (v: unknown, status = 200) => new Response(wire(v), { status, headers: { 'X-Diagnostic-Processing-State': 'PENDING' } })
let fetcher: ReturnType<typeof vi.fn<typeof fetch>>
beforeEach(() => { sessionStorage.clear(); window.location.hash = ''; fetcher = vi.fn<typeof fetch>(); vi.stubGlobal('fetch', fetcher) })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers() })

describe('care boundary and diagnostic states', () => {
  it('preserves native int64 counts/revisions in display and mutation wire tokens', () => {
    const raw = readCareJson(wire(diagnosticPage()).replace('"cycleNumber":1', '"cycleNumber":9223372036854775807'))
    const result = page(raw, 'diagnostics', 'venue-2021')!
    expect(integer(result.items[0].cycleNumber)).toBe('9223372036854775807')
    expect(wire({ expectedRevision: revision(result.items[0].cycleNumber) })).toBe('{"expectedRevision":9223372036854775807}')
    expect(integer(readCareJson('9223372036854775808'))).toBeNull()
    expect(page({ ...diagnosticPage(), items: [{ ...base(), vehicleId: 'other' }] }, 'diagnostics', 'venue-2021')).toBeNull()
  })
  it('shows unknown baseline without implying health or a known MIL', async () => {
    fetcher.mockResolvedValue(response({ ...diagnosticPage(), items: [] }))
    render(<Diagnostics vehicle="venue-2021" />)
    expect(await screen.findByText(/No diagnostic snapshots accepted/)).toBeTruthy()
    expect(screen.getByText(/No projected diagnostic events/)).toBeTruthy()
  })
  it.each(['COMPLETE', 'PARTIAL', 'UNSUPPORTED', 'UNAVAILABLE', 'TIMEOUT', 'FAILED'])('distinguishes %s scope outcome and MIL unknown/off', async outcome => {
    const snapshot = structuredClone(examples.snapshot)
    snapshot.ecus[0].outcome = outcome
    snapshot.ecus[0].categories.forEach(c => { c.outcome = outcome })
    fetcher.mockResolvedValue(response({ ...diagnosticPage(), latestSnapshot: { snapshotId: id, receivedAt: snapshot.capturedAt, snapshot, freshness: outcome === 'COMPLETE' ? 'FRESH' : 'UNKNOWN' } }))
    render(<Diagnostics vehicle="venue-2021" />)
    await screen.findByText(/synthetic evidence, not your vehicle/)
    expect(screen.getAllByText(outcome === 'COMPLETE' ? /Complete empty read for this scope only/ : /No complete absence evidence/)).toHaveLength(3)
    expect(screen.getAllByText(/MIL: Reported off/).length).toBeGreaterThan(0)
  })
  it('keeps unknown definitions, recurrence, missing observation and unsupported MIL explicit', async () => {
    const e = { ...base(), observationState: 'NOT_OBSERVED', cycleNumber: 3, mil: { outcome: 'UNSUPPORTED', illuminated: null } }
    fetcher.mockResolvedValue(response({ ...diagnosticPage(), items: [e] }))
    render(<Diagnostics vehicle="venue-2021" />)
    expect(await screen.findByText(/Unknown definition/)).toBeTruthy()
    expect(screen.getByText(/Cycle 3/)).toBeTruthy()
    expect(screen.getByText(/MIL: Unknown/)).toBeTruthy()
    expect(screen.getByText(/Not observed in a qualifying/)).toBeTruthy()
  })
  it('keeps a complete category read distinct from the incomplete overall ECU result', async () => {
    const snapshot = structuredClone(examples.snapshot)
    snapshot.ecus[0].outcome = 'PARTIAL'; snapshot.ecus[0].categories[1].outcome = 'PARTIAL'
    fetcher.mockResolvedValue(response({ ...diagnosticPage(), latestSnapshot: { snapshotId: id, receivedAt: snapshot.capturedAt, snapshot, freshness: 'UNKNOWN' } }))
    render(<Diagnostics vehicle="venue-2021" />)
    await screen.findByText(/overall PARTIAL/)
    expect(screen.getAllByText(/Complete empty read for this category only/)).toHaveLength(2)
    expect(screen.getAllByText(/No complete absence evidence/)).toHaveLength(1)
  })
  it('shows transition truncation and pending detail header', async () => {
    fetcher.mockResolvedValue(response({ ...base(), recentTransitions: [{ kind: 'OBSERVED', capturedAt: '2026-10-22T12:00:00Z', snapshotId: id }], hasEarlierTransitions: true }))
    render(<DiagnosticDetail vehicle="venue-2021" id={id} />)
    expect(await screen.findByText(/Earlier transitions exist/)).toBeTruthy()
    expect(screen.getByText(/Processing: PENDING/)).toBeTruthy()
  })
  it('ages fresh observations without polling and does not promote unknown freshness', async () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-22T12:00:22Z'))
    fetcher.mockResolvedValue(response({ ...diagnosticPage(), items: [{ ...base(), freshness: 'FRESH' }] }))
    render(<Diagnostics vehicle="venue-2021" />)
    await act(async () => { await Promise.resolve() })
    expect(screen.getByText(/Freshness: FRESH/)).toBeTruthy()
    act(() => vi.advanceTimersByTime(310_000))
    expect(screen.getByText(/Freshness: STALE/)).toBeTruthy()
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it('bounds a stalled request and ignores its late success', async () => {
    vi.useFakeTimers()
    let resolve!: (r: Response) => void
    fetcher.mockImplementation(() => new Promise(r => { resolve = r }))
    render(<Diagnostics vehicle="venue-2021" />)
    expect(screen.getByRole('status').textContent).toContain('Loading')
    act(() => vi.advanceTimersByTime(15_001))
    expect(screen.getByRole('alert').textContent).toContain('timed out')
    await act(async () => { resolve(response(diagnosticPage())) })
    expect(screen.queryByText(/Unknown definition/)).toBeNull()
  })
  it('resamples browser time immediately for a newly captured observation, not the previous timer tick', async () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-22T12:00:22Z'))
    fetcher.mockResolvedValueOnce(response({ ...diagnosticPage(), items: [{ ...base(), freshness: 'FRESH' }] }))
      .mockResolvedValueOnce(response({ ...diagnosticPage(), items: [{ ...base(), freshness: 'FRESH', lastAppliedAt: '2026-10-22T12:01:21Z' }] }))
    render(<Diagnostics vehicle="venue-2021" />)
    await act(async () => { await Promise.resolve() })
    vi.setSystemTime(new Date('2026-10-22T12:01:22Z'))
    fireEvent.click(screen.getByRole('button', { name: 'Refresh newest' }))
    await act(async () => { await Promise.resolve() })
    expect(screen.getByText(/Freshness: FRESH/).textContent).toContain('12:01:21Z')
    expect(screen.queryByText(/Freshness: CLOCK_WARNING/)).toBeNull()
  })
  it('retains same-resource data with an explicit error but rejects wrong vehicle payload', async () => {
    fetcher.mockResolvedValueOnce(response(diagnosticPage())).mockResolvedValueOnce(response({ ...diagnosticPage(), items: [{ ...base(), vehicleId: 'wrong' }] }))
    render(<Diagnostics vehicle="venue-2021" />)
    await screen.findByText(/Unknown definition/)
    fireEvent.click(screen.getByRole('button', { name: 'Refresh newest' }))
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', expect.stringContaining('Unreadable response'))
    expect(screen.getByText(/Retained last-known data/)).toBeTruthy()
  })
  it('never renders a late old vehicle response after navigation even if abort is ignored', async () => {
    let resolve!: (r: Response) => void
    fetcher.mockImplementationOnce(() => new Promise(r => { resolve = r })).mockResolvedValue(response({ ...diagnosticPage(), items: [] }))
    window.location.hash = '#/diagnostics'
    const view = render(<App vehicle="venue-2021" />)
    view.rerender(<App vehicle="other" />)
    await screen.findByText(/No diagnostic snapshots accepted/)
    await act(async () => { resolve(response(diagnosticPage())) })
    expect(screen.queryByText(/Unknown definition/)).toBeNull()
    expect(screen.getByText('VEHICLE / other')).toBeTruthy()
  })
  it('does not show the previous page when cursor changes and recovers an expired page', async () => {
    fetcher.mockResolvedValueOnce(response({ ...diagnosticPage(), nextCursor: 'signed' })).mockResolvedValueOnce(response({ code: 'PAGE_CHANGED' }, 409)).mockResolvedValueOnce(response(diagnosticPage()))
    render(<Diagnostics vehicle="venue-2021" />)
    await screen.findByText(/Unknown definition/)
    fireEvent.click(screen.getByRole('button', { name: 'Next page (older evidence)' }))
    expect(screen.queryByText(/Unknown definition/)).toBeNull()
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', expect.stringContaining('Refresh newest'))
    fireEvent.click(screen.getByRole('button', { name: 'Refresh newest' }))
    await screen.findByText(/Unknown definition/)
  })
})
describe('manual maintenance and advisory workflows', () => {
  it('leaves profile/schedule unselected and history unknown rather than inferred from trips', async () => {
    fetcher.mockImplementation(async url => response(String(url).endsWith('service-profile') ? profile() : empty()))
    render(<Maintenance vehicle="venue-2021" />)
    await screen.findByText(/Manual service profile/)
    expect((screen.getByLabelText('Usage schedule') as HTMLSelectElement).value).toBe('UNSET')
    expect((screen.getByLabelText('Vehicle applicability') as HTMLSelectElement).value).toBe('UNCONFIRMED')
    expect(screen.getByText(/No manual records/)).toBeTruthy()
    expect(screen.getAllByText(/not estimated trip distance/).length).toBeGreaterThan(0)
  })
  it('requires a correction reason and sends exact decimal input and expected revision', async () => {
    fetcher.mockImplementation(async (_url, options) => options?.method === 'PATCH' ? response({ id, revision: 2, recordedAt: '2026-10-23T12:00:00Z' }) : response({ item: entry(), recentRevisions: [], hasEarlierRevisions: true }))
    render(<MaintenanceDetail vehicle="venue-2021" id={id} />)
    const reason = await screen.findByLabelText('Correction reason')
    expect(reason.hasAttribute('required')).toBe(true)
    fireEvent.change(reason, { target: { value: 'Transcription fix' } })
    fireEvent.change(screen.getByLabelText('Actual odometer value'), { target: { value: '18000.126' } })
    fireEvent.submit(screen.getByRole('button', { name: 'Save correction' }).closest('form')!)
    await waitFor(() => expect(fetcher.mock.calls.some(([, o]) => o?.method === 'PATCH')).toBe(true))
    const body = fetcher.mock.calls.find(([, o]) => o?.method === 'PATCH')![1]!.body as string
    expect(body).toContain('"expectedRevision":1'); expect(body).toContain('"odometerValue":"18000.126"'); expect(body).toContain('Transcription fix')
  })
  it.each(['OIL_FILTER', 'DIAGNOSTIC', 'TEMPERATURE'])('offers only allowed %s advisory transitions and keeps provenance', async kind => {
    fetcher.mockResolvedValue(response({ item: rec({ kind }), recentRevisions: [], hasEarlierRevisions: false }))
    render(<RecommendationDetail vehicle="venue-2021" id={id} />)
    await screen.findByRole('button', { name: 'Acknowledge advisory' })
    expect(screen.getByText('Frozen rule evidence and provenance')).toBeTruthy()
    expect(!!screen.queryByRole('button', { name: 'Complete workflow (not repair)' })).toBe(kind !== 'OIL_FILTER')
    if (kind !== 'OIL_FILTER') expect(screen.getByText(/SIMULATED · synthetic/)).toBeTruthy()
  })
  it('has no actions for terminal recommendations and does not equate an empty list to no need', async () => {
    fetcher.mockResolvedValueOnce(response({ item: rec({ state: 'DISMISSED' }), recentRevisions: [], hasEarlierRevisions: false })).mockResolvedValue(response(empty()))
    const view = render(<RecommendationDetail vehicle="venue-2021" id={id} />)
    await screen.findByText(/DIAGNOSTIC · DISMISSED/)
    expect(screen.queryByRole('button', { name: /Acknowledge|Dismiss|Complete workflow/ })).toBeNull()
    view.unmount(); render(<Recommendations vehicle="venue-2021" />)
    expect(await screen.findByText(/No recommendations on this page/)).toBeTruthy()
  })
})
function CommandProbe() {
  const command = useCommand('/api/v1/vehicles/venue-2021/maintenance-records', () => {})
  return <><button onClick={() => { void command.submit('POST', { expectedRevision: revision(0), notes: 'test' }) }}>Submit</button><button onClick={command.retry}>Retry</button><p>{command.message}</p><p>{command.pending ? 'Unconfirmed' : 'No pending command'}</p></>
}
describe('submission identity', () => {
  it('survives ambiguous network response and remount/reload, resending byte-identical body', async () => {
    fetcher.mockRejectedValueOnce(Error('Connection lost')).mockResolvedValueOnce(response({ id, revision: 1, recordedAt: '2026-10-23T12:00:00Z' }))
    const view = render(<CommandProbe />)
    fireEvent.click(screen.getByText('Submit')); await screen.findByText(/Connection lost/)
    expect(screen.getByText('Unconfirmed')).toBeTruthy()
    const body = fetcher.mock.calls[0][1]!.body
    view.unmount(); render(<CommandProbe />)
    expect(screen.getByText('Unconfirmed')).toBeTruthy(); expect(fetcher).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByText('Retry'))
    await screen.findByText(/Saved. Refreshing/)
    expect(fetcher.mock.calls[1][1]!.body).toBe(body)
    expect(sessionStorage.length).toBe(0)
  })
  it('releases a definite conflict and explains revision recovery', async () => {
    fetcher.mockResolvedValue(response({ code: 'REVISION_CONFLICT' }, 409))
    render(<CommandProbe />); fireEvent.click(screen.getByText('Submit'))
    await screen.findByText(/Another change was saved first/)
    expect(screen.getByText('No pending command')).toBeTruthy()
    expect(sessionStorage.length).toBe(0)
  })
  it('keeps an unreadable success receipt unconfirmed rather than silently permitting a new record', async () => {
    fetcher.mockResolvedValue(response({ id, revision: '1', recordedAt: '2026-10-23T12:00:00Z' }))
    render(<CommandProbe />); fireEvent.click(screen.getByText('Submit'))
    await screen.findByText(/Unreadable receipt/)
    expect(screen.getByText('Unconfirmed')).toBeTruthy()
    expect(sessionStorage.length).toBe(1)
  })
})
