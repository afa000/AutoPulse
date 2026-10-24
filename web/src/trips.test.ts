import { describe, expect, it } from 'vitest'
import examples from '../../contracts/examples/trip-api-examples.json'
import { coverage, duration, estimatedDistance, parseTrip, parseTripPage } from './trips'

describe('trip display boundary', () => {
  it('accepts shared contract examples without changing precise timestamp strings or ratios', () => {
    const raw = structuredClone(examples.detail)
    raw.startedAt = '2026-10-20T12:00:02.123456Z'
    const trip = parseTrip(raw, 'venue-2021')!
    expect(trip.startedAt).toBe(raw.startedAt)
    expect(estimatedDistance(trip)).toBe('0.04 km')
    expect(coverage(trip)).toBe('6 / 6 s supported speed intervals')
    expect(duration(3661.999)).toBe('1h 1m 1s')
    expect(parseTripPage(examples.list, 'venue-2021')).not.toBeNull()
    expect(parseTripPage(examples.emptyList, 'venue-2021')).not.toBeNull()
  })
  it.each([
    { vehicleId: 'another-vehicle' }, { tripId: 'bad' }, { source: ['SIMULATED'] }, { status: 'CANDIDATE' },
    { revision: 0 }, { startedAt: '2026-02-30T12:00:00Z' }, { sampleCount: -1 }, { finalization: true },
    { distance: { ...examples.detail.distance, coveredSeconds: 7 } },
    { distance: { ...examples.detail.distance, kilometres: { numerator: 1, denominator: 0 } } },
    { metrics: { ...examples.detail.metrics, rpm: { count: 0, sum: 0, min: null, max: null } } },
  ])('rejects malformed or wrong-vehicle detail: %j', change => {
    expect(parseTrip({ ...examples.detail, ...change }, 'venue-2021')).toBeNull()
  })
  it('keeps unavailable distance distinct from a supported zero-distance interval', () => {
    const missing = parseTrip({ ...examples.detail, distance: { ...examples.detail.distance, kilometres: null, coveredSeconds: 0 } }, 'venue-2021')!
    const zero = parseTrip({ ...examples.detail, distance: { ...examples.detail.distance, kilometres: { numerator: 0, denominator: 3600 } } }, 'venue-2021')!
    expect(estimatedDistance(missing)).toBe('Unavailable'); expect(estimatedDistance(zero)).toBe('0 km')
  })
  it('rejects oversized/duplicate pages, malformed cursors, and internal/superseded list rows', () => {
    for (const change of [{ items: Array(21).fill(examples.detail) }, { items: [examples.detail, examples.detail] },
      { nextCursor: 'x'.repeat(2049) }, { generation: 1 }, { items: [], nextCursor: 'next' },
      { items: [{ ...examples.detail, status: 'SUPERSEDED' }] }]) {
      expect(parseTripPage({ ...examples.list, ...change }, 'venue-2021')).toBeNull()
    }
  })
})
