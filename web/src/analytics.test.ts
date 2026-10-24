import { expect, it } from 'vitest'
import { displayRatio, exactText, isExactTotal, parseAnalyticsJson } from './analytics'
import { estimatedDistance, parseTrip, parseTripPage } from './trips'
import { parseHistory } from './history'
import examples from '../../contracts/examples/trip-api-examples.json'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const total = (token: string) => (parseAnalyticsJson(`{"sum":${token}}`) as { sum: Parameters<typeof displayRatio>[0] }).sum
it.each(['4e308', '-4e308', '2.7e309', '0', '-0', '1.00000000000000000000001', '1e-999'])('retains the exact source token %s', token => {
  expect(exactText(total(token))).toBe(token)
})
it('divides exact totals and ratios before binary64 conversion, including cancellation and null/zero', () => {
  expect(displayRatio(total('2.7e309'), 27)).toBe(1e308)
  expect(displayRatio(total('-4e308'), 4)).toBe(-1e308)
  expect(displayRatio(total('4e308'), total('4e308'))).toBe(1)
  expect(displayRatio(total('0'), 27)).toBe(0)
  expect(displayRatio(total('1e-999'), 1)).toBeNull()
  expect(displayRatio(total('1e999'), 1)).toBeNull()
  expect(isExactTotal(total('1e-999999999999999999'))).toBe(false)
  expect(displayRatio(total('1e308'), 27)).toBe(1e308 / 27) // exact backend sum after +/- cancellation
  expect(displayRatio(total('1'), 0)).toBeNull()
  expect(isExactTotal(null)).toBe(false)
})
it.each(['NaN', 'Infinity', '01', '+1', '1.', '.1'])('rejects malformed numeric JSON token %s', token => {
  expect(() => total(token)).toThrow()
})
it('rejects conflicting duplicate keys and forged totals without rounding invalid counts into valid metadata', () => {
  expect(() => parseAnalyticsJson('{"sum":1,"sum":2}')).toThrow()
  expect(exactText((parseAnalyticsJson('{"sum":1,"sum":1}') as { sum: Parameters<typeof exactText>[0] }).sum)).toBe('1')
  for (const sum of ['"4e308"', '{"value":"4e308","isLosslessNumber":true}', 'null']) {
    const raw = JSON.stringify(examples.detail).replace('"sum":4000', `"sum":${sum}`)
    expect(parseTrip(parseAnalyticsJson(raw), 'venue-2021')).toBeNull()
  }
  for (const revision of ['9007199254740992', '1.000000000000000001']) {
    const raw = JSON.stringify(examples.detail).replace('"revision":1', `"revision":${revision}`)
    expect(parseTrip(parseAnalyticsJson(raw), 'venue-2021')).toBeNull()
  }
  const underflow = JSON.stringify(examples.detail).replace(/"min":-?\d+/, '"min":1e-999')
  expect(underflow).toContain('"min":1e-999')
  expect(parseTrip(parseAnalyticsJson(underflow), 'venue-2021')).toBeNull()
})
it('loads every shared trip/history example through the actual text boundary', () => {
  const trip = parseTrip(parseAnalyticsJson(JSON.stringify(examples.detail)), 'venue-2021')!
  expect(parseTripPage(parseAnalyticsJson(JSON.stringify(examples.list)), trip.vehicleId)).not.toBeNull()
  expect(estimatedDistance(trip)).toBe('0.04 km')
  for (const name of ['historyRaw', 'historyAggregate', 'historyEmpty', 'historyExpired'] as const) {
    const page = examples[name]
    expect(parseHistory(parseAnalyticsJson(JSON.stringify(page)), trip, page, page.resolution as 'raw' | '1m', 200)).not.toBeNull()
  }
  const big = { ...trip, distance: { ...trip.distance, kilometres: { numerator: total('4e308'), denominator: total('4e308') } } }
  expect(estimatedDistance(big)).toBe('1 km')
  expect(estimatedDistance({ ...big, distance: { ...big.distance, kilometres: { numerator: total('4e999'), denominator: 1 } } })).toContain('Outside display range')
})
it('keeps large aggregate totals as exact evidence while calculating safe finite means', () => {
  const raw = JSON.stringify(examples.historyAggregate).replace('"sum":4000', '"sum":3e308').replace('"min":1000', '"min":1e308').replace('"max":2000', '"max":1e308')
  const page = parseHistory(parseAnalyticsJson(raw), parseTrip(examples.detail, 'venue-2021')!, examples.historyRaw, '1m', 200)!
  expect(page.rows[0].values.rpm).toBe(1e308)
  expect(page.rows[0].exactTotals?.rpm).toBe('3e308')
  expect(page.rows[0].values.coolantTempC).toBe(60 / 2)
})
it('reads the shared extreme JSON tokens losslessly rather than importing rounded module numbers', () => {
  const fixture = parseAnalyticsJson(readFileSync(resolve('../contracts/examples/analytics-numeric-cases.json'), 'utf8')) as {
    cases: { count: number; sum: Parameters<typeof displayRatio>[0] | null; expectedMean: string | null }[];
    distance: { numerator: Parameters<typeof displayRatio>[0]; denominator: Parameters<typeof displayRatio>[0]; expectedKilometres: string }
  }
  for (const row of fixture.cases) expect(row.sum === null ? null : displayRatio(row.sum, row.count)).toBe(row.expectedMean === null ? null : Number(row.expectedMean))
  expect(displayRatio(fixture.distance.numerator, fixture.distance.denominator)).toBe(Number(fixture.distance.expectedKilometres))
})
