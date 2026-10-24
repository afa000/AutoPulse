import { expect, it } from 'vitest'
import examples from '../../contracts/examples/trip-api-examples.json'
import { historyWindow, micros, parseHistory, plotRows, utc } from './history'
import { parseTrip } from './trips'

const trip = parseTrip(examples.detail, 'venue-2021')!
const range = { from: examples.historyRaw.from, to: examples.historyRaw.to }
it('constructs exact half-open bounds including the closed trip end without millisecond rounding', () => {
  const value = { ...trip, startedAt: '2026-10-20T12:00:02.123456Z', endedAt: '2026-10-20T12:00:08.999999Z' }
  const window = historyWindow(value, 'raw', 0)
  expect(window.from).toBe(value.startedAt); expect(window.to).toBe('2026-10-20T12:00:09.000000Z')
  for (const time of ['0001-01-01T00:00:00.000001Z', '1969-12-31T23:59:59.999999Z', '2026-10-20T12:00:02.123456Z']) expect(utc(micros(time))).toBe(time)
  expect(() => micros('2026-02-30T12:00:00Z')).toThrow()
  expect(() => historyWindow({ ...trip, endedAt: '9999-12-31T23:59:59.999999Z' }, 'raw', 100000000)).toThrow()
})
it('splits a full 24-hour raw drive into bounded windows without dropping its final reading', () => {
  const full = { ...trip, startedAt: '2026-10-20T12:00:00Z', endedAt: '2026-10-21T12:00:00Z' }
  const first = historyWindow(full, 'raw', 0), last = historyWindow(full, 'raw', 1)
  expect(first.more).toBe(true); expect(last.more).toBe(false); expect(first.to).toBe(last.from)
  expect(micros(last.to) - micros(last.from)).toBe(1n)
})
it('parses shared raw/aggregate fixtures and retains zeros, nulls and independent sample counts', () => {
  const raw = parseHistory(examples.historyRaw, trip, range, 'raw', 200)!
  expect(raw.rows[0].values.speedKph).toBe(0); expect(raw.rows[1].values.coolantTempC).toBeNull()
  const aggregate = parseHistory(examples.historyAggregate, trip, range, '1m', 200)!
  expect(aggregate.rows[0].values.rpm).toBeCloseTo(4000 / 3)
  expect(aggregate.rows[0].counts.coolantTempC).toBe(2); expect(aggregate.rows[0].partial).toBe(true)
})
it.each([
  { generation: 1 }, { resolution: '5m' }, { from: '2026-10-20T12:00:01Z' }, { nextCursor: 'x'.repeat(2049) },
  { points: Array(201).fill(examples.historyRaw.points[0]) }, { points: [], nextCursor: 'next' },
  { points: [{ ...examples.historyRaw.points[0], reading: { ...examples.historyRaw.points[0].reading, vehicleId: 'wrong' } }] },
  { points: [{ ...examples.historyRaw.points[0], reading: { ...examples.historyRaw.points[0].reading, deviceId: 'wrong' } }] },
  { points: [{ ...examples.historyRaw.points[0], reading: { ...examples.historyRaw.points[0].reading, source: 'OBD' } }] },
  { points: [...examples.historyRaw.points].reverse() },
])('rejects wrong context, malformed identity or unbounded history: %j', change => {
  expect(parseHistory({ ...examples.historyRaw, ...change }, trip, range, 'raw', 200)).toBeNull()
})
it('rejects aggregate provenance, bucket and null-statistic inconsistencies', () => {
  for (const change of [{ deviceCount: 2 }, { sources: ['OBD', 'SIMULATED'] }, { bucketEnd: '2026-10-20T12:01:00Z' },
    { partial: false }, { firstObservedAt: '2026-10-20T12:00:01Z' },
    { metrics: { ...examples.historyAggregate.points[0].metrics, rpm: { count: 0, sum: 1, min: null, max: null } } }]) {
    expect(parseHistory({ ...examples.historyAggregate, points: [{ ...examples.historyAggregate.points[0], ...change }] }, trip, range, '1m', 200)).toBeNull()
  }
})
it('breaks raw lines at long gaps, equal-time ties and boot changes, while retaining nulls', () => {
  const page = parseHistory(examples.historyRaw, trip, range, 'raw', 200)!
  expect(plotRows(page)).toHaveLength(3); expect(plotRows(page)[1].coolantTempC).toBeNull()
  for (const change of [{ time: page.rows[0].time }, { boot: 'new-boot' }, { time: '2026-10-20T12:00:20Z' }]) {
    const changed = { ...page, rows: [page.rows[0], { ...page.rows[1], ...change }] }
    expect(plotRows(changed)).toHaveLength(3); expect(plotRows(changed)[1].rpm).toBeNull()
  }
})
it('represents expired history separately from never-observed empty data', () => {
  const expired = parseHistory(examples.historyExpired, trip, range, 'raw', 200)!
  expect(expired.rows).toEqual([]); expect(expired.availability.rawState).toBe('EXPIRED')
  expect(expired.availability.availableResolutions).not.toContain('raw')
})
