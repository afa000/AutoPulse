import { isObject } from './telemetry'
import { displayRatio, exactNonnegative, exactPositive, isExactTotal, type ExactTotal } from './analytics'

/** Display-only numbers: never regenerate telemetry or opaque cursors from these. */
export const metricNames = ['rpm', 'speedKph', 'coolantTempC', 'engineLoadPct', 'throttlePositionPct',
  'intakeTempC', 'mafGps', 'fuelLevelPct', 'controlModuleVoltageV'] as const
export type MetricName = typeof metricNames[number]
export type Metric = { count: number; sum: ExactTotal | null; min: number | null; max: number | null }
export type Trip = {
  tripId: string; vehicleId: string; primaryDeviceId: string; source: 'SIMULATED' | 'OBD'
  startedAt: string; endedAt: string | null; observedThrough: string; confirmedAt: string | null
  status: 'ACTIVE' | 'COMPLETED' | 'SUPERSEDED'; completionReason: string | null
  algorithmVersion: string; revision: number; finalization: 'PROVISIONAL' | 'FINAL'; sampleCount: number
  metrics: Record<MetricName, Metric>
  distance: { estimated: true; kilometres: { numerator: ExactTotal; denominator: ExactTotal } | null; coveredSeconds: number; durationSeconds: number }
  quality: string[]; supersededBy: string[]
}
export type TripPage = { items: Trip[]; nextCursor: string | null; generation: string }
export const uuid = (value: unknown): value is string => typeof value === 'string' && /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i.test(value)
const finite = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value)
const nonnegative = (value: unknown): value is number => finite(value) && value >= 0
const integer = (value: unknown): value is number => nonnegative(value) && Number.isSafeInteger(value)
const text = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0
function timestamp(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,6})?Z$/.test(value)) return false
  const date = new Date(value)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 19) === value.slice(0, 19)
}
export function parseTrip(value: unknown, vehicle: string): Trip | null {
  if (!isObject(value) || !uuid(value.tripId) || value.vehicleId !== vehicle || !text(value.primaryDeviceId)
    || typeof value.source !== 'string' || !['SIMULATED', 'OBD'].includes(value.source)
    || !timestamp(value.startedAt) || !timestamp(value.observedThrough)
    || !(value.endedAt === null || timestamp(value.endedAt)) || !(value.confirmedAt === null || timestamp(value.confirmedAt))
    || typeof value.status !== 'string' || !['ACTIVE', 'COMPLETED', 'SUPERSEDED'].includes(value.status)
    || !(value.completionReason === null || typeof value.completionReason === 'string' && ['ENGINE_OFF', 'SIGNAL_TIMEOUT', 'SOURCE_CHANGED', 'DURATION_LIMIT'].includes(value.completionReason))
    || !text(value.algorithmVersion) || !integer(value.revision) || value.revision < 1
    || typeof value.finalization !== 'string' || !['PROVISIONAL', 'FINAL'].includes(value.finalization)
    || !integer(value.sampleCount) || value.sampleCount < 1 || !isObject(value.metrics)) return null
  for (const name of metricNames) {
    const metric = value.metrics[name]
    if (!isObject(metric) || !integer(metric.count) || metric.count > value.sampleCount) return null
    if (metric.count === 0) { if (metric.sum !== null || metric.min !== null || metric.max !== null) return null }
    else if (!isExactTotal(metric.sum) || !finite(metric.min) || !finite(metric.max) || metric.min > metric.max) return null
  }
  const distance = value.distance
  if (!isObject(distance) || distance.estimated !== true || !nonnegative(distance.durationSeconds)
    || !nonnegative(distance.coveredSeconds) || distance.coveredSeconds > distance.durationSeconds) return null
  const km = distance.kilometres
  if (km !== null && (!isObject(km) || !exactNonnegative(km.numerator) || !exactPositive(km.denominator))) return null
  if ((distance.coveredSeconds === 0) !== (km === null)) return null
  if (!Array.isArray(value.quality) || value.quality.length > 100 || !value.quality.every(text)
    || !Array.isArray(value.supersededBy) || value.supersededBy.length > 100 || !value.supersededBy.every(uuid)) return null
  return value as Trip
}
export function parseTripPage(value: unknown, vehicle: string): TripPage | null {
  if (!isObject(value) || !Array.isArray(value.items) || value.items.length > 20
    || typeof value.generation !== 'string' || !/^[0-9]{1,20}$/.test(value.generation)
    || !(value.nextCursor === null || typeof value.nextCursor === 'string' && /^[A-Za-z0-9_-]{1,2048}$/.test(value.nextCursor))) return null
  const items = value.items.map(item => parseTrip(item, vehicle))
  if (items.some(item => item === null || item.status === 'SUPERSEDED') || new Set(items.map(item => item?.tripId)).size !== items.length) return null
  if (items.length === 0 && value.nextCursor !== null) return null
  return value as TripPage
}
export const displayNumber = (value: number, digits = 1) => !Number.isFinite(value) ? 'Outside display range'
  : value !== 0 && (Math.abs(value) >= 1e12 || Math.abs(value) < 0.001) ? value.toExponential(6).replace(/\.?(0+)(?=e)/, '')
    : value.toLocaleString(undefined, { maximumFractionDigits: digits })
export function duration(seconds: number) {
  const rounded = Math.floor(seconds)
  return `${Math.floor(rounded / 3600)}h ${Math.floor(rounded % 3600 / 60)}m ${rounded % 60}s`
}
export function estimatedDistance(trip: Trip) {
  const km = trip.distance.kilometres
  if (km === null) return 'Unavailable'
  const value = displayRatio(km.numerator, km.denominator)
  return value !== null ? `${displayNumber(value, 3)} km` : 'Outside display range (exact ratio retained)'
}
export function coverage(trip: Trip) {
  const { coveredSeconds, durationSeconds } = trip.distance
  return `${displayNumber(coveredSeconds, 3)} / ${displayNumber(durationSeconds, 3)} s supported speed intervals`
}
export const completionLabel = (reason: string | null) => ({ ENGINE_OFF: 'Engine-off confirmed', SIGNAL_TIMEOUT: 'Signal timeout (not confirmed off)',
  SOURCE_CHANGED: 'Source changed', DURATION_LIMIT: 'Duration limit' })[reason ?? ''] ?? 'Still active / awaiting completion'
