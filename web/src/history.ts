import { isObject, parseSnapshot } from './telemetry'
import { metricNames, type Metric, type Trip } from './trips'
import { displayRatio, exactText, isExactTotal } from './analytics'

export type Resolution = 'raw' | '1m' | '5m'
export const chartMetrics = [['rpm', 'Engine speed', 'RPM'], ['speedKph', 'Road speed', 'km/h'],
  ['coolantTempC', 'Coolant temperature', '°C'], ['engineLoadPct', 'Engine load', '%']] as const
export type ChartMetric = typeof chartMetrics[number][0]
export type HistoryRow = { time: string; end: string | null; boot: string | null; partial: boolean; sampleCount: number;
  values: Record<ChartMetric, number | null>; counts: Record<ChartMetric, number>;
  exactTotals?: Record<string, string | null>; outsideDisplayRange?: ChartMetric[] }
export type HistoryPage = { from: string; to: string; resolution: Resolution; rows: HistoryRow[]; nextCursor: string | null; generation: string;
  availability: { rawState: 'AVAILABLE' | 'PARTIALLY_EXPIRED' | 'EXPIRED'; rawUnavailableBefore: string | null; availableResolutions: Resolution[]; incomplete: boolean } }
export const resolutions: Resolution[] = ['raw', '1m', '5m']
const finite = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v)
const count = (v: unknown): v is number => finite(v) && Number.isSafeInteger(v) && v >= 0

/** Exact microseconds for request bounds/comparison; JS Date is used only for whole seconds. */
export function micros(value: unknown): bigint {
  if (typeof value !== 'string' || !/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?Z$/.test(value) || value.startsWith('0000')) throw new Error('Invalid UTC time')
  const base = new Date(`${value.slice(0, 19)}Z`)
  if (!Number.isFinite(base.getTime()) || base.toISOString().slice(0, 19) !== value.slice(0, 19)) throw new Error('Invalid UTC time')
  return BigInt(base.getTime()) * 1000n + BigInt(value.includes('.') ? value.slice(20, -1).padEnd(6, '0') : '0')
}
export function utc(value: bigint): string {
  const fraction = ((value % 1_000_000n) + 1_000_000n) % 1_000_000n
  const prefix = new Date(Number((value - fraction) / 1000n)).toISOString().slice(0, 19)
  const result = `${prefix}.${fraction.toString().padStart(6, '0')}Z`
  micros(result) // Do not silently wrap beyond the supported four-digit UTC years.
  return result
}
export function historyWindow(trip: Trip, resolution: Resolution, index: number) {
  const start = micros(trip.startedAt), end = micros(trip.endedAt ?? trip.observedThrough) + 1n
  if (end <= start || !Number.isSafeInteger(index) || index < 0) throw new Error('Invalid trip history bounds')
  const span = BigInt(resolution === 'raw' ? 86400 : 31 * 86400) * 1_000_000n
  const from = start + BigInt(index) * span, to = from + span < end ? from + span : end
  if (from >= end) throw new Error('History window is outside the trip')
  return { from: utc(from), to: utc(to), more: to < end }
}
export function parseHistory(value: unknown, trip: Trip, range: { from: string; to: string }, resolution: Resolution, limit: number): HistoryPage | null {
  try {
    if (!isObject(value) || value.resolution !== resolution || micros(value.from) !== micros(range.from) || micros(value.to) !== micros(range.to)
      || !Array.isArray(value.points) || value.points.length > limit || typeof value.generation !== 'string' || !/^[0-9]{1,20}$/.test(value.generation)
      || !(value.nextCursor === null || typeof value.nextCursor === 'string' && /^[A-Za-z0-9_-]{1,2048}$/.test(value.nextCursor))) return null
    const a = value.availability
    if (!isObject(a) || typeof a.rawState !== 'string' || !['AVAILABLE', 'PARTIALLY_EXPIRED', 'EXPIRED'].includes(a.rawState)
      || typeof a.incomplete !== 'boolean' || !Array.isArray(a.availableResolutions) || !a.availableResolutions.every(r => resolutions.includes(r as Resolution))
      || new Set(a.availableResolutions).size !== a.availableResolutions.length) return null
    if (a.rawState === 'AVAILABLE') { if (a.rawUnavailableBefore !== null) return null }
    else micros(a.rawUnavailableBefore)
    const lower = micros(range.from), upper = micros(range.to)
    const rows: HistoryRow[] = []
    for (const point of value.points) {
      if (!isObject(point)) return null
      if (resolution === 'raw') {
        if (point.kind !== 'raw') return null
        const snapshot = parseSnapshot(JSON.stringify({ reading: point.reading, receivedAt: point.receivedAt }), trip.vehicleId)
        if (!snapshot || snapshot.reading.source !== trip.source || snapshot.reading.deviceId !== trip.primaryDeviceId) return null
        const r = snapshot.reading, at = micros(r.recordedAt)
        if (at < lower || at >= upper || rows.length > 0 && at < micros(rows.at(-1)!.time)) return null
        rows.push({ time: r.recordedAt, end: null, boot: r.bootId, sampleCount: 1, partial: false,
          values: Object.fromEntries(chartMetrics.map(([name]) => [name, r[name] ?? null])) as HistoryRow['values'],
          counts: Object.fromEntries(chartMetrics.map(([name]) => [name, r[name] == null ? 0 : 1])) as HistoryRow['counts'] })
      } else {
        if (point.kind !== 'aggregate' || typeof point.partial !== 'boolean' || !count(point.sampleCount) || point.sampleCount < 1 || !isObject(point.metrics)
          || point.deviceCount !== 1 || !Array.isArray(point.sources) || point.sources.length !== 1 || point.sources[0] !== trip.source) return null
        const begin = micros(point.bucketStart), end = micros(point.bucketEnd), first = micros(point.firstObservedAt), last = micros(point.lastObservedAt)
        if (begin < lower || end > upper || begin >= end || first < begin || last >= end || first > last
          || rows.length > 0 && begin < micros(rows.at(-1)!.end)) return null
        const width = BigInt(resolution === '1m' ? 60 : 300) * 1_000_000n
        const aligned = begin - ((begin % width) + width) % width
        const expectedStart = aligned < lower ? lower : aligned, expectedEnd = aligned + width > upper ? upper : aligned + width
        if (begin !== expectedStart || end !== expectedEnd || point.partial !== (begin !== aligned || end !== aligned + width)) return null
        for (const name of metricNames) {
          const m = point.metrics[name]
          if (!isObject(m) || !count(m.count) || m.count > point.sampleCount) return null
          if (m.count === 0 ? m.sum !== null || m.min !== null || m.max !== null : !isExactTotal(m.sum) || !finite(m.min) || !finite(m.max) || m.min > m.max) return null
        }
        const stats = point.metrics as Record<ChartMetric, Metric>
        rows.push({ time: point.bucketStart as string, end: point.bucketEnd as string, boot: null, partial: point.partial, sampleCount: point.sampleCount,
          values: Object.fromEntries(chartMetrics.map(([name]) => [name, stats[name].count === 0 ? null : displayRatio(stats[name].sum!, stats[name].count)])) as HistoryRow['values'],
          exactTotals: Object.fromEntries(metricNames.map(name => [name, (point.metrics as Record<string, Metric>)[name].sum === null ? null : exactText((point.metrics as Record<string, Metric>)[name].sum!)])),
          outsideDisplayRange: chartMetrics.flatMap(([name]) => stats[name].count > 0 && displayRatio(stats[name].sum!, stats[name].count) === null ? [name] : []),
          counts: Object.fromEntries(chartMetrics.map(([name]) => [name, stats[name].count])) as HistoryRow['counts'] })
      }
    }
    if (rows.length === 0 && value.nextCursor !== null || resolution === 'raw' && a.rawState === 'EXPIRED' && rows.length > 0) return null
    return { from: value.from as string, to: value.to as string, resolution, rows, nextCursor: value.nextCursor as string | null,
      generation: value.generation, availability: a as HistoryPage['availability'] }
  } catch { return null }
}
export type PlotRow = { x: number } & Record<ChartMetric, number | null>
export function plotRows(page: HistoryPage): PlotRow[] {
  const output: PlotRow[] = [], origin = micros(page.from)
  page.rows.forEach((row, index) => {
    const x = Number(micros(row.time) - origin) / 1_000_000, previous = page.rows[index - 1]
    if (page.resolution === 'raw' && previous && (micros(row.time) <= micros(previous.time) || micros(row.time) - micros(previous.time) > 5_000_000n || row.boot !== previous.boot)) {
      output.push({ x: (x + Number(micros(previous.time) - origin) / 1_000_000) / 2, rpm: null, speedKph: null, coolantTempC: null, engineLoadPct: null })
    }
    output.push({ x, ...row.values })
  })
  return output
}
