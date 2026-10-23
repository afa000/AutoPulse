import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import TripCharts from './TripCharts'
import { historyWindow, type HistoryPage } from './history'
import { parseTrip } from './trips'
import examples from '../../contracts/examples/trip-api-examples.json'
vi.mock('./HistoryPlot', () => ({ HistoryPlot: ({ page }: { page: HistoryPage }) => <p data-testid="plot">{page.resolution} · {page.rows.length} displayed points</p> }))
const trip = parseTrip(examples.detail, 'venue-2021')!
const range = historyWindow(trip, 'raw', 0)
const raw = { ...examples.historyRaw, from: range.from, to: range.to }
const aggregate = { ...examples.historyAggregate, from: range.from, to: range.to,
  points: [{ ...examples.historyAggregate.points[0], bucketEnd: range.to }] }
const fetchMock = vi.fn<typeof fetch>()
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'X-Trip-Processing-State': 'CURRENT' } })
beforeEach(() => { fetchMock.mockReset(); vi.stubGlobal('fetch', fetchMock) })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
it('requests precise trip-filtered bounds and exposes measurements with an accessible table', async () => {
  fetchMock.mockResolvedValue(response(raw))
  render(<TripCharts trip={trip} generation="1" />)
  await screen.findByTestId('plot')
  const query = new URL(String(fetchMock.mock.calls[0][0]), 'http://localhost').searchParams
  expect(query.get('tripId')).toBe(trip.tripId); expect(query.get('to')).toBe('2026-10-20T12:00:08.000001Z'); expect(query.get('limit')).toBe('200')
  fireEvent.click(screen.getByText('Measurement values and sample coverage (accessible table)'))
  expect(screen.getByRole('table').textContent).toContain('Unavailable (0/1)')
  expect(screen.getByRole('table').textContent).toContain('0 (1/1)')
})
it('replaces pages and resets the cursor when resolution changes', async () => {
  fetchMock.mockResolvedValueOnce(response({ ...raw, nextCursor: 'next-token' }))
    .mockResolvedValueOnce(response({ ...raw, points: raw.points.slice(1) }))
    .mockResolvedValueOnce(response(aggregate))
  render(<TripCharts trip={trip} generation="1" />); await screen.findByText('raw · 3 displayed points')
  fireEvent.click(screen.getByRole('button', { name: 'Next history page' }))
  await screen.findByText('raw · 2 displayed points'); expect(String(fetchMock.mock.calls[1][0])).toContain('cursor=next-token')
  fireEvent.change(screen.getByLabelText('History resolution'), { target: { value: '1m' } })
  await screen.findByText('1m · 1 displayed points')
  expect(String(fetchMock.mock.calls[2][0])).not.toContain('cursor=')
})
it('blocks mixing a newer history generation with an older summary', async () => {
  fetchMock.mockResolvedValue(response({ ...raw, generation: '2' }))
  render(<TripCharts trip={trip} generation="1" />)
  expect((await screen.findByRole('alert')).textContent).toContain('History changed since this summary')
  expect(screen.queryByTestId('plot')).toBeNull()
})
it('retains labeled old points on a failed page rather than claiming empty complete history', async () => {
  fetchMock.mockResolvedValueOnce(response({ ...raw, nextCursor: 'next-token' })).mockResolvedValueOnce(response({ code: 'PAGE_CHANGED' }, 409))
  render(<TripCharts trip={trip} generation="1" />); await screen.findByTestId('plot')
  fireEvent.click(screen.getByRole('button', { name: 'Next history page' }))
  expect((await screen.findByRole('alert')).textContent).toContain('Refresh the trip summary')
  expect(screen.getByText(/Retained last-known history page/)).toBeTruthy(); expect(screen.getByTestId('plot')).toBeTruthy()
})
it('shows explicit expired availability and allows an available aggregate resolution', async () => {
  fetchMock.mockResolvedValueOnce(response({ ...examples.historyExpired, from: range.from, to: range.to })).mockResolvedValueOnce(response(aggregate))
  render(<TripCharts trip={trip} generation="1" />)
  await screen.findByText(/Raw history is expired/)
  expect(screen.queryByTestId('plot')).toBeNull(); expect(screen.getByText(/not proof of no drive/)).toBeTruthy()
  fireEvent.change(screen.getByLabelText('History resolution'), { target: { value: '1m' } })
  await screen.findByTestId('plot')
})
it('ignores a stale resolution request completing after cancellation', async () => {
  let resolve!: (r: Response) => void
  fetchMock.mockReturnValueOnce(new Promise<Response>(done => { resolve = done })).mockResolvedValueOnce(response(aggregate))
  render(<TripCharts trip={trip} generation="1" />)
  const signal = fetchMock.mock.calls[0][1]!.signal!
  fireEvent.change(screen.getByLabelText('History resolution'), { target: { value: '1m' } })
  await screen.findByText('1m · 1 displayed points'); expect(signal.aborted).toBe(true)
  await act(async () => resolve(response(raw)))
  await waitFor(() => expect(screen.queryByText('raw · 3 displayed points')).toBeNull())
})
