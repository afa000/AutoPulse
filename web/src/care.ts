import { LosslessNumber, parse, stringify } from 'lossless-json'
import { isObject } from './telemetry'
import { uuid } from './trips'

export type ObjectData = Record<string, unknown>
export const object = (value: unknown): value is ObjectData => isObject(value) && !(value instanceof LosslessNumber)
export const text = (value: unknown): string => typeof value === 'string' ? value : 'Unknown'
export const wire = (value: unknown): string => stringify(value) ?? 'null'
export const readCareJson = (value: string): unknown => parse(value)
export function integer(value: unknown): string | null {
  const token = value instanceof LosslessNumber ? value.value : typeof value === 'number' && Number.isSafeInteger(value) ? String(value) : ''
  return /^(0|[1-9][0-9]*)$/.test(token) && BigInt(token) <= 9223372036854775807n ? token : null
}
export const time = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?Z$/.test(v) && Number.isFinite(Date.parse(v))
const choice = (v: unknown, values: string[]) => typeof v === 'string' && values.includes(v)
const outcome = (v: unknown) => choice(v, ['COMPLETE', 'PARTIAL', 'UNSUPPORTED', 'UNAVAILABLE', 'TIMEOUT', 'FAILED'])
const source = (v: unknown) => choice(v, ['SIMULATED', 'OBD'])
const freshness = (v: unknown) => choice(v, ['FRESH', 'CURRENT', 'STALE', 'UNKNOWN', 'CLOCK_WARNING'])
export function mil(v: unknown): v is ObjectData {
  return object(v) && outcome(v.outcome) && v.outcome !== 'PARTIAL' && (v.outcome === 'COMPLETE' ? typeof v.illuminated === 'boolean' : v.illuminated === null)
}
export function event(v: unknown, vehicle: string): v is ObjectData {
  return object(v) && v.vehicleId === vehicle && uuid(v.eventId) && typeof v.code === 'string' && typeof v.ecuId === 'string'
    && typeof v.deviceId === 'string' && source(v.source) && choice(v.category, ['PENDING', 'CONFIRMED', 'PERMANENT'])
    && choice(v.observationState, ['PRESENT', 'NOT_OBSERVED']) && integer(v.cycleNumber) !== null && integer(v.positiveObservationCount) !== null
    && time(v.firstObservedAt) && time(v.lastObservedAt) && time(v.lastAppliedAt) && outcome(v.currentReadOutcome)
    && freshness(v.freshness) && mil(v.mil) && object(v.definition) && object(v.evidence)
}
function latest(v: unknown, vehicle: string): boolean {
  if (v === null) return true
  if (!object(v) || !uuid(v.snapshotId) || !time(v.receivedAt) || !freshness(v.freshness) || !object(v.snapshot)) return false
  const s = v.snapshot
  return s.vehicleId === vehicle && source(s.source) && time(s.capturedAt) && integer(s.diagnosticSequence) !== null
    && typeof s.deviceId === 'string' && Array.isArray(s.ecus) && s.ecus.length > 0 && s.ecus.length <= 8
    && s.ecus.every(e => object(e) && typeof e.ecuId === 'string' && outcome(e.outcome) && mil(e.mil)
      && Array.isArray(e.categories) && e.categories.length === 3 && e.categories.every(c => object(c)
        && choice(c.category, ['PENDING', 'CONFIRMED', 'PERMANENT']) && outcome(c.outcome)
        && Array.isArray(c.codes) && c.codes.every(code => typeof code === 'string')))
}
export type CarePage = { items: ObjectData[]; nextCursor: string | null; generation: string; processingState: string; latestSnapshot?: unknown; oilAssessment?: unknown }
export function page(raw: unknown, kind: 'diagnostics' | 'maintenance-records' | 'recommendations', vehicle: string): CarePage | null {
  if (!object(raw) || !Array.isArray(raw.items) || raw.items.length > 100
    || !(raw.nextCursor === null || typeof raw.nextCursor === 'string') || typeof raw.generation !== 'string'
    || !/^[0-9]+$/.test(raw.generation) || !choice(raw.processingState, ['CURRENT', 'PENDING', 'REVIEW'])) return null
  const validate = kind === 'diagnostics' ? (v: unknown) => event(v, vehicle) : kind === 'maintenance-records' ? entry : recommendation
  if (!raw.items.every(validate) || (kind === 'diagnostics' && !latest(raw.latestSnapshot, vehicle))) return null
  return raw as CarePage
}
export function entry(v: unknown): v is ObjectData {
  return object(v) && uuid(v.entryId) && integer(v.revision) !== null && choice(v.kind, ['ODOMETER', 'SERVICE'])
    && (v.kind === 'SERVICE' ? v.serviceType === 'OIL_FILTER' : v.serviceType === null) && time(v.observedAt)
    && typeof v.odometerValue === 'string' && /^(0|[1-9][0-9]{0,6})(\.[0-9]{1,3})?$/.test(v.odometerValue)
    && choice(v.odometerUnit, ['MI', 'KM']) && (v.notes === null || typeof v.notes === 'string')
    && (v.correctionReason === null || typeof v.correctionReason === 'string')
}
export function recommendation(v: unknown): v is ObjectData {
  return object(v) && uuid(v.id) && integer(v.revision) !== null && time(v.generatedAt) && typeof v.ruleVersion === 'string'
    && choice(v.kind, ['OIL_FILTER', 'DIAGNOSTIC', 'TEMPERATURE'])
    && choice(v.state, ['OPEN', 'ACKNOWLEDGED', 'COMPLETED', 'DISMISSED', 'EXPIRED']) && object(v.evidence) && object(v.currentObservation)
}
export function profile(v: unknown): ObjectData | null {
  if (!object(v) || integer(v.revision) === null || !object(v.oilAssessment)) return null
  const p = v.profile
  return p === null || object(p) && integer(p.revision) === integer(v.revision)
    && choice(p.applicability, ['UNCONFIRMED', 'US_2021_VENUE_G16']) && choice(p.usageSchedule, ['UNSET', 'NORMAL', 'SEVERE'])
    && choice(p.distanceUnit, ['MI', 'KM']) && typeof p.timeZone === 'string' && typeof p.normalOilSpecificationConfirmed === 'boolean' ? v : null
}
export function detail(raw: unknown, kind: 'maintenance-records' | 'recommendations', id: string): ObjectData | null {
  return object(raw) && (kind === 'maintenance-records' ? entry(raw.item) && raw.item.entryId === id : recommendation(raw.item) && raw.item.id === id)
    && Array.isArray(raw.recentRevisions) && raw.recentRevisions.length <= 100 && raw.recentRevisions.every(r => object(r) && integer(r.revision) !== null && time(r.recordedAt) && object(r.evidence))
    && typeof raw.hasEarlierRevisions === 'boolean' ? raw : null
}
export const endpoint = (vehicle: string, resource: string) => `/api/v1/vehicles/${encodeURIComponent(vehicle)}/${resource}`
export const revision = (v: unknown) => new LosslessNumber(integer(v) ?? '0')
export const safeLink = (v: unknown) => typeof v === 'string' && /^https:\/\//.test(v) ? v : undefined
