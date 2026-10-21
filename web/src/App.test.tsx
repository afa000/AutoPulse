import { StrictMode } from 'react'
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { freshness, parseSnapshot } from './telemetry'
import type { EventStream } from './useLiveTelemetry'
import freshnessCases from '../../contracts/examples/live-freshness.json'

class FakeStream implements EventStream {
  readyState = 0
  closed = false
  listeners = new Map<string, ((event: MessageEvent) => void)[]>()
  addEventListener(type: string, listener: (event: MessageEvent) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }
  emit(type: string, data = '') { act(() => this.listeners.get(type)?.forEach(listener => listener(new MessageEvent(type, { data })))) }
  close() { this.closed = true }
}
const at = '2026-10-20T12:00:00Z'
const payload = (changes = {}) => ({ reading: { deviceId: 'sim-device-001', vehicleId: 'venue-2021',
  bootId: '123e4567-e89b-12d3-a456-426614174000', sequenceNumber: 0, recordedAt: at,
  source: 'SIMULATED', rpm: 0, speedKph: 0, coolantTempC: null, engineLoadPct: null,
  milOn: false, mafGps: null, fuelLevelPct: null, ...changes }, receivedAt: at })

describe('live page state rules', () => {
  let streams: FakeStream[]
  let factory: ReturnType<typeof vi.fn<(url: string) => FakeStream>>
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(new Date(at)); streams = []
    factory = vi.fn(() => { const stream = new FakeStream(); streams.push(stream); return stream })
  })
  afterEach(() => { cleanup(); vi.useRealTimers() })

  it('shows an honest empty state then preserves null, zero, false and source labels', () => {
    render(<App factory={factory} />)
    expect(screen.getByTestId('connection').textContent).toContain('connecting')
    streams[0].emit('open'); streams[0].emit('empty', '{"vehicleId":"venue-2021"}')
    expect(screen.getByText('No readings yet')).toBeTruthy()
    streams[0].emit('telemetry', JSON.stringify(payload()))
    expect(screen.getByTestId('metric-0').textContent).toBe('0RPM')
    expect(screen.getByTestId('metric-1').textContent).toBe('0km/h')
    expect(screen.getByTestId('metric-2').textContent).toBe('Unavailable')
    expect(screen.getByTestId('source').textContent).toBe('SIMULATED')
    expect(screen.getByTestId('mil').textContent).toBe('Reported off')
    expect(screen.getByTestId('engine-state').textContent).toContain('Engine-off reading')
    streams[0].emit('telemetry', JSON.stringify(payload({ milOn: null, rpm: null })))
    expect(screen.getByTestId('mil').textContent).toBe('Unknown')
    expect(screen.getByTestId('engine-state').textContent).toContain('Unknown')
  })

  it('ages without events and heartbeats or reconnect snapshots do not refresh measurement time', () => {
    render(<App factory={factory} />); streams[0].emit('open')
    streams[0].emit('telemetry', JSON.stringify(payload()))
    expect(screen.getByTestId('freshness').textContent).toBe('Recent measurement')
    act(() => vi.advanceTimersByTime(7000))
    streams[0].emit('message', 'keepalive')
    expect(screen.getByTestId('freshness').textContent).toBe('Stale measurement')
    streams[0].emit('error')
    expect(screen.getByTestId('connection').textContent).toContain('reconnecting')
    expect(screen.getByTestId('metric-0').textContent).toBe('0RPM')
    streams[0].emit('open'); streams[0].emit('telemetry', JSON.stringify(payload()))
    expect(screen.getByTestId('freshness').textContent).toBe('Stale measurement')
  })

  it('separates recent receipts from old measurements and flags future simulation time', () => {
    render(<App factory={factory} />)
    const old = payload({ recordedAt: '2026-10-20T11:59:00Z' })
    streams[0].emit('telemetry', JSON.stringify(old))
    expect(screen.getByTestId('freshness').textContent).toBe('Stale measurement')
    expect(screen.getByText('60s ago')).toBeTruthy(); expect(screen.getByText('0s ago')).toBeTruthy()
    streams[0].emit('telemetry', JSON.stringify(payload({ recordedAt: '2026-10-20T12:01:00Z' })))
    expect(screen.getByTestId('freshness').textContent).toContain('freshness unknown')
    expect(screen.getByText('ahead of browser clock')).toBeTruthy()
  })

  it('retains last-known data on malformed events, ignores unknown events, handles terminal errors', () => {
    render(<App factory={factory} />)
    streams[0].emit('telemetry', JSON.stringify(payload()))
    streams[0].emit('telemetry', '{')
    expect(screen.getByRole('alert').textContent).toContain('Unreadable')
    expect(screen.getByTestId('metric-0').textContent).toBe('0RPM')
    streams[0].emit('future-protocol-event', '{}')
    streams[0].readyState = 2; streams[0].emit('error')
    expect(screen.getByTestId('connection').textContent).toContain('unavailable')
    streams[0].emit('empty', '{"vehicleId":"wrong"}')
    expect(screen.getByTestId('metric-0').textContent).toBe('0RPM')
  })

  it('cleans StrictMode subscriptions, switches vehicle, and ignores obsolete callbacks', () => {
    const view = render(<StrictMode><App factory={factory} /></StrictMode>)
    expect(streams.filter(stream => !stream.closed)).toHaveLength(1)
    const old = streams.at(-1)!
    old.emit('telemetry', JSON.stringify(payload()))
    view.rerender(<StrictMode><App vehicle="other" factory={factory} /></StrictMode>)
    expect(old.closed).toBe(true)
    old.emit('telemetry', JSON.stringify(payload({ rpm: 9999 })))
    expect(screen.getByTestId('metric-0').textContent).toBe('Unavailable')
    const next = streams.at(-1)!
    next.emit('telemetry', JSON.stringify(payload({ vehicleId: 'other', rpm: 850 })))
    expect(screen.getByTestId('metric-0').textContent).toBe('850RPM')
    view.unmount(); expect(streams.every(stream => stream.closed)).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('shows unavailable if EventSource construction fails', () => {
    render(<App factory={() => { throw new Error('unsupported') }} />)
    expect(screen.getByTestId('connection').textContent).toContain('unavailable')
    expect(screen.getByText('Stream unavailable')).toBeTruthy()
  })
})

it('uses the documented age/skew thresholds without reordering events in the browser', () => {
  expect(freshness(at, Date.parse(at) + 5999, 6)).toBe('fresh')
  expect(freshness(at, Date.parse(at) + 6000, 6)).toBe('stale')
  expect(freshness(at, Date.parse(at) - 2000, 6)).toBe('fresh')
  expect(freshness(at, Date.parse(at) - 2001, 6)).toBe('clock-warning')
})

it.each(freshnessCases)('matches shared freshness example $expected', testCase => {
  expect(freshness(testCase.recordedAt, Date.parse(testCase.now), 6)).toBe(testCase.expected)
})

it('guards malformed identities, sensors, timestamps and vehicle mismatches', () => {
  for (const change of [{ vehicleId: 'wrong' }, { bootId: 'bad' }, { rpm: false }, { engineLoadPct: 101 },
    { recordedAt: '2026-02-30T12:00:00Z' }, { sequenceNumber: -1 }, { source: ['SIMULATED'] }]) {
    expect(parseSnapshot(JSON.stringify(payload(change)), 'venue-2021')).toBeNull()
  }
  expect(parseSnapshot(JSON.stringify(payload()), 'venue-2021')).not.toBeNull()
})
