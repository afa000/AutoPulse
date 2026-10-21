/** Display-only boundary: never use JS numbers to regenerate ingestion/replay JSON. */
export type Reading = {
  deviceId: string; vehicleId: string; bootId: string; sequenceNumber: number
  recordedAt: string; source: 'SIMULATED' | 'OBD'
  rpm?: number | null; speedKph?: number | null; coolantTempC?: number | null
  engineLoadPct?: number | null; milOn?: boolean | null
  mafGps?: number | null; fuelLevelPct?: number | null
}
export type Snapshot = { reading: Reading; receivedAt: string }
export const isObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

function timestamp(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,6})?Z$/.test(value)) return false
  const time = new Date(value)
  return Number.isFinite(time.getTime()) && time.toISOString().slice(0, 19) === value.slice(0, 19)
}

export function parseSnapshot(raw: string, vehicle: string): Snapshot | null {
  try {
    const value: unknown = JSON.parse(raw)
    if (!isObject(value) || !timestamp(value.receivedAt) || !isObject(value.reading)) return null
    const r = value.reading
    if (r.vehicleId !== vehicle || typeof r.deviceId !== 'string' || !r.deviceId.trim()
      || typeof r.bootId !== 'string' || !/^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i.test(r.bootId)
      || typeof r.sequenceNumber !== 'number' || !Number.isInteger(r.sequenceNumber) || r.sequenceNumber < 0
      || !timestamp(r.recordedAt) || typeof r.source !== 'string' || !['SIMULATED', 'OBD'].includes(r.source)) return null
    for (const field of ['rpm', 'speedKph', 'coolantTempC', 'engineLoadPct', 'mafGps', 'fuelLevelPct']) {
      const sensor = r[field]
      if (sensor != null && (typeof sensor !== 'number' || !Number.isFinite(sensor))) return null
      if (typeof sensor === 'number' && ['rpm', 'speedKph', 'mafGps', 'engineLoadPct', 'fuelLevelPct'].includes(field) && sensor < 0) return null
      if (typeof sensor === 'number' && ['engineLoadPct', 'fuelLevelPct'].includes(field) && sensor > 100) return null
    }
    if (r.milOn != null && typeof r.milOn !== 'boolean') return null
    return value as Snapshot
  } catch { return null }
}

export function freshness(recordedAt: string, now: number, staleSeconds: number) {
  const age = (now - Date.parse(recordedAt)) / 1000
  if (age < -2) return 'clock-warning'
  return age >= staleSeconds ? 'stale' : 'fresh'
}

export function ageLabel(timestamp: string, now: number) {
  const seconds = (now - Date.parse(timestamp)) / 1000
  if (seconds < -2) return 'ahead of browser clock'
  return `${Math.max(0, Math.floor(seconds))}s ago`
}
