import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import App from './App'
import examples from '../../contracts/examples/trip-api-examples.json'

const id = examples.detail.tripId
const secondId = '30000000-0000-4000-8000-000000000002'
const fetchMock = vi.fn<typeof fetch>()
const response = (value: unknown, status = 200, processing = 'CURRENT') => new Response(JSON.stringify(value), {
  status, headers: { 'Content-Type': 'application/json', 'X-Trip-Processing-State': processing },
})
const page = (trip = examples.detail, nextCursor: string | null = null) => ({ items: [trip], generation: '1', nextCursor })
function navigate(hash: string) {
  act(() => { window.history.pushState(null, '', hash); window.dispatchEvent(new HashChangeEvent('hashchange')) })
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
beforeEach(() => {
  window.history.replaceState(null, '', '/#/trips'); fetchMock.mockReset(); vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers(); window.history.replaceState(null, '', '/') })

it('loads bounded pages, shows context and replaces rather than accumulating old trips', async () => {
  fetchMock.mockResolvedValueOnce(response(page(examples.detail, 'next-token')))
    .mockResolvedValueOnce(response(page({ ...examples.detail, tripId: secondId, startedAt: '2026-10-19T12:00:02Z' })))
  render(<App />)
  expect(screen.getByRole('status').textContent).toContain('Loading')
  await screen.findByRole('link', { name: /Trip starting/ })
  expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/vehicles/venue-2021/trips?limit=20')
  expect(screen.getByText('0.04 km')).toBeTruthy(); expect(screen.getByText(/synthetic data/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Next page (older trips)' }))
  await waitFor(() => expect(screen.getByRole('link', { name: /Trip starting/ }).getAttribute('href')).toContain(secondId))
  expect(fetchMock.mock.calls[1][0]).toContain('&cursor=next-token')
  expect(screen.queryByText('2026-10-20T12:00:02Z')).toBeNull()
  expect((screen.getByRole('button', { name: 'Next page (older trips)' }) as HTMLButtonElement).disabled).toBe(true)
})

it('retains last-known page on a changed-generation error and restarts with no cursor', async () => {
  fetchMock.mockResolvedValueOnce(response(page(examples.detail, 'next-token')))
    .mockResolvedValueOnce(response({ code: 'PAGE_CHANGED' }, 409))
    .mockResolvedValueOnce(response(examples.emptyList))
  render(<App />); await screen.findByRole('link', { name: /Trip starting/ })
  fireEvent.click(screen.getByRole('button', { name: 'Next page (older trips)' }))
  expect((await screen.findByRole('alert')).textContent).toContain('page expired')
  expect(screen.getByText(/Showing retained last-known/)).toBeTruthy()
  expect(screen.getByRole('link', { name: /Trip starting/ })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Refresh newest trips' }))
  await screen.findByText('No saved trips on this page')
  expect(fetchMock.mock.calls.at(-1)![0]).not.toContain('cursor=')
})

it.each(['PENDING', 'REVIEW', 'UNKNOWN'])('never treats %s processing as complete history', async processing => {
  fetchMock.mockResolvedValue(response(examples.emptyList, 200, processing))
  render(<App />); await screen.findByText('No saved trips on this page')
  expect(screen.getByText(processing === 'PENDING' ? /Trip processing is pending/ : processing === 'REVIEW' ? /Trip processing needs review/ : /Processing status is unavailable/)).toBeTruthy()
})

it('distinguishes an unknown vehicle from known empty and offers refresh', async () => {
  fetchMock.mockResolvedValue(response({ code: 'UNKNOWN_VEHICLE' }, 404))
  render(<App vehicle="missing" />)
  expect((await screen.findByRole('alert')).textContent).toContain('not available')
  expect(screen.queryByText('No saved trips on this page')).toBeNull()
})

it('opens a detail deep link, preserves null statistics, and refreshes a changed revision', async () => {
  navigate(`#/trips/${id}`)
  const absent = { count: 0, sum: null, min: null, max: null }
  const first = { ...examples.detail, metrics: { ...examples.detail.metrics, coolantTempC: absent } }
  fetchMock.mockResolvedValueOnce(response(first)).mockResolvedValueOnce(response({ ...first, revision: 2, finalization: 'FINAL' }))
  render(<App />); await screen.findByText('1 / trip-v1-draft')
  expect(screen.getAllByText('Unavailable')).toHaveLength(3)
  expect(screen.getByText('0 / 4')).toBeTruthy()
  expect(screen.getByText(/Provisional result/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Refresh trip' }))
  await screen.findByText('2 / trip-v1-draft')
  expect(screen.queryByText(/Provisional result/)).toBeNull()
  expect(screen.getByText(/measurement times in UTC/)).toBeTruthy()
})

it('rejects a wrong-trip response and retains the last-known revision after failed refresh', async () => {
  navigate(`#/trips/${id}`)
  fetchMock.mockResolvedValueOnce(response(examples.detail)).mockResolvedValueOnce(response({ ...examples.detail, tripId: secondId }))
  render(<App />); await screen.findByText('1 / trip-v1-draft')
  fireEvent.click(screen.getByRole('button', { name: 'Refresh trip' }))
  expect((await screen.findByRole('alert')).textContent).toContain('Unreadable')
  expect(screen.getByText('1 / trip-v1-draft')).toBeTruthy(); expect(screen.getByText(/Showing retained last-known/)).toBeTruthy()
})

it('renders active observation boundaries and superseded replacement links honestly', async () => {
  navigate(`#/trips/${id}`)
  fetchMock.mockResolvedValueOnce(response({ ...examples.detail, status: 'ACTIVE', endedAt: null, confirmedAt: null, completionReason: null }))
    .mockResolvedValueOnce(response({ ...examples.detail, status: 'SUPERSEDED', supersededBy: [secondId] }))
  render(<App />); await screen.findByText('Not completed')
  expect(screen.getByText(/not the browser clock/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Refresh trip' }))
  expect((await screen.findByRole('link', { name: `Replacement trip ${secondId}` })).getAttribute('href')).toBe(`#/trips/${secondId}`)
  expect(screen.getByText(/reference-only/)).toBeTruthy()
})

it('aborts obsolete vehicle requests and ignores a transport completing after cancellation', async () => {
  const old = deferred<Response>(), current = deferred<Response>()
  fetchMock.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
  const view = render(<App />)
  const signal = fetchMock.mock.calls[0][1]!.signal!
  view.rerender(<App vehicle="other" />); expect(signal.aborted).toBe(true)
  await act(async () => old.resolve(response(page())))
  expect(screen.queryByRole('link', { name: /Trip starting/ })).toBeNull()
  await act(async () => current.resolve(response(page({ ...examples.detail, vehicleId: 'other' }))))
  await screen.findByRole('link', { name: /Trip starting/ })
  view.unmount(); expect(fetchMock.mock.calls[1][1]!.signal!.aborted).toBe(true)
})

it('times out stalled requests and does not accept their late response', async () => {
  vi.useFakeTimers(); const pending = deferred<Response>(); fetchMock.mockReturnValue(pending.promise)
  render(<App />)
  await act(async () => { vi.advanceTimersByTime(15_000) })
  expect(screen.getByRole('alert').textContent).toContain('timed out')
  expect(fetchMock.mock.calls[0][1]!.signal!.aborted).toBe(true)
  await act(async () => pending.resolve(response(page())))
  expect(screen.queryByRole('link', { name: /Trip starting/ })).toBeNull()
})

it('switches navigation without leaving live streams open and handles unknown routes', async () => {
  window.history.replaceState(null, '', '/')
  const streams: { close: ReturnType<typeof vi.fn> }[] = []
  const factory = vi.fn(() => { const stream = { readyState: 0, addEventListener: vi.fn(), close: vi.fn() }; streams.push(stream); return stream })
  fetchMock.mockImplementation(async () => response(examples.emptyList))
  const view = render(<StrictMode><App factory={factory} /></StrictMode>)
  navigate('#/trips'); await screen.findByText('No saved trips on this page')
  expect(streams.every(stream => stream.close.mock.calls.length > 0)).toBe(true)
  expect(screen.getByRole('link', { name: 'Trips' }).getAttribute('aria-current')).toBe('page')
  navigate('#/'); expect(screen.getByTestId('connection')).toBeTruthy()
  navigate('#/unknown'); expect(screen.getByText('Page not found')).toBeTruthy()
  view.unmount(); expect(streams.every(stream => stream.close.mock.calls.length > 0)).toBe(true)
})
