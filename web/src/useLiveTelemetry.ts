import { useEffect, useState } from 'react'
import { isObject, parseSnapshot, type Snapshot } from './telemetry'

export type StreamState = 'connecting' | 'live' | 'reconnecting' | 'unavailable'
export interface EventStream {
  readyState: number
  addEventListener(type: string, listener: (event: MessageEvent) => void): void
  close(): void
}
export type StreamFactory = (url: string) => EventStream
const nativeStream: StreamFactory = url => new EventSource(url)
type State = { vehicle: string; connection: StreamState; snapshot: Snapshot | null; empty: boolean; warning: string | null }

export function useLiveTelemetry(vehicle: string, factory: StreamFactory = nativeStream) {
  const initial: State = { vehicle, connection: 'connecting', snapshot: null, empty: false, warning: null }
  const [state, setState] = useState(initial)
  useEffect(() => {
    let active = true
    let stream: EventStream
    const update = (change: Partial<State>) => {
      if (active) setState(previous => ({ ...(previous.vehicle === vehicle ? previous : initial), ...change }))
    }
    try { stream = factory(`/api/v1/vehicles/${encodeURIComponent(vehicle)}/live`) }
    catch { update({ connection: 'unavailable' }); return () => { active = false } }
    stream.addEventListener('open', () => update({ connection: 'live' }))
    stream.addEventListener('error', () => update({ connection: stream.readyState === 2 ? 'unavailable' : 'reconnecting' }))
    stream.addEventListener('telemetry', event => {
      const snapshot = parseSnapshot(event.data, vehicle)
      if (snapshot) update({ snapshot, empty: false, warning: null })
      else update({ warning: 'Unreadable stream event. Keeping last-known values.' })
    })
    stream.addEventListener('empty', event => {
      try {
        const value: unknown = JSON.parse(event.data)
        if (isObject(value) && value.vehicleId === vehicle) update({ snapshot: null, empty: true, warning: null })
        else update({ warning: 'Unreadable empty-state event.' })
      } catch { update({ warning: 'Unreadable empty-state event.' }) }
    })
    return () => { active = false; stream.close() }
    // This effect owns one subscription per vehicle/factory. Obsolete callbacks cannot update it.
  }, [vehicle, factory])
  return state.vehicle === vehicle ? state : initial
}
