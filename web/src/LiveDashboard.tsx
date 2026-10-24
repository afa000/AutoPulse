import { useEffect, useState } from 'react'
import { ageLabel, freshness } from './telemetry'
import { useLiveTelemetry, type StreamFactory } from './useLiveTelemetry'
import { PageFrame } from './PageFrame'

function positiveSetting(raw: unknown, fallback: number) {
  const value = Number(raw)
  return Number.isFinite(value) && value > 0 ? value : fallback
}
const configuredVehicle = import.meta.env.VITE_VEHICLE_ID || 'venue-2021'
const configuredStale = positiveSetting(import.meta.env.VITE_STALE_SECONDS, 6)

export default function LiveDashboard({ vehicle = configuredVehicle, staleSeconds = configuredStale, factory }: {
  vehicle?: string; staleSeconds?: number; factory?: StreamFactory
}) {
  const state = useLiveTelemetry(vehicle, factory)
  const [now, setNow] = useState(Date.now)
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer) }, [])
  const snapshot = state.snapshot
  const reading = snapshot?.reading
  const age = reading ? freshness(reading.recordedAt, now, staleSeconds) : null
  const source = reading?.source ?? 'NO SOURCE DATA'
  const retained = state.connection !== 'live'
  const freshnessText = age === 'clock-warning' ? 'Clock / simulation-time warning — freshness unknown'
    : age === 'stale' ? 'Stale measurement' : age === 'fresh' ? 'Recent measurement' : 'No measurement yet'
  const metrics = [
    ['Engine speed', reading?.rpm, 'RPM'], ['Road speed', reading?.speedKph, 'km/h'],
    ['Coolant temperature', reading?.coolantTempC, '°C'], ['Engine load', reading?.engineLoadPct, '%'],
  ] as const
  return <PageFrame page="live">
    <section className="intro"><div><p className="eyebrow">VEHICLE / {vehicle}</p><h1>A clearer view<br />of the last reading.</h1>
      <p className="subtitle">Live server updates. Honest measurement age. No hardware assumptions.</p></div>
      <div className="source-panel"><span className="eyebrow">REPORTED DATA SOURCE</span><strong className="source" data-testid="source">{source}</strong>
        <p>{reading?.source === 'SIMULATED' ? 'Synthetic signals · not verified vehicle characteristics' : 'Source does not establish device connectivity'}</p></div></section>
    <section className="status-bar" aria-label="Telemetry status">
      <p role="status" data-testid="connection"><span className="status-dot" aria-hidden="true" />Server connection: <strong>{state.connection}</strong></p>
      <p data-testid="freshness">{freshnessText}</p>
    </section>
    {state.warning && <p role="alert" className="notice">{state.warning}</p>}
    {age === 'clock-warning' && <p className="notice">Measurement time is ahead of this browser by more than 2 seconds. Check source clocks or accelerated simulation time.</p>}
    {retained && reading && <p className="notice">Connection interrupted or reconnecting. These are retained last-known values, not a live vehicle status.</p>}
    {!reading && <section className="empty"><h2>{state.empty ? 'No readings yet' : state.connection === 'unavailable' ? 'Stream unavailable' : 'Waiting for a reading'}</h2>
      <p>{state.connection === 'unavailable' ? 'Check the vehicle identifier and local backend, then reload to reconnect.' : 'This page will update when persisted telemetry is available. No readings are being invented.'}</p></section>}
    <section className={`metrics ${age === 'stale' || retained ? 'last-known' : ''}`} aria-label="Last-known measurements">
      {metrics.map(([label, value, unit], index) => <article className="metric" key={label} aria-label={label}>
        <p className="metric-label"><span className="metric-index">0{index + 1}</span>{label}</p>
        <p className="value" data-testid={`metric-${index}`}>{value == null ? <span className="unavailable">Unavailable</span> : <>{value.toLocaleString(undefined, { maximumFractionDigits: 1 })}<span className="unit">{unit}</span></>}</p>
        <p className="metric-caption">{reading ? 'Last-known measurement' : 'Awaiting persisted data'}</p></article>)}
    </section>
    <section className="details" aria-label="Reading context">
      <article><h2>Last-known engine state</h2><p data-testid="engine-state">{reading?.rpm == null ? 'Unknown — RPM unavailable' : reading.rpm === 0 ? 'Engine-off reading (0 RPM)' : 'Engine-running reading'}</p><small>This reading can be stale. Zero speed alone says nothing about engine state.</small></article>
      <article><h2>Reported MIL</h2><p data-testid="mil">{reading?.milOn == null ? 'Unknown' : reading.milOn ? 'Reported on' : 'Reported off'}</p><small>Reported lamp state only; no diagnostic interpretation.</small></article>
      <article><h2>Optional sensors</h2><p>MAF: {reading?.mafGps == null ? 'Unavailable' : `${reading.mafGps} g/s`}</p><p>Fuel level: {reading?.fuelLevelPct == null ? 'Unavailable' : `${reading.fuelLevelPct}%`}</p></article>
    </section>
    <section className="timing" aria-label="Measurement and receipt times">
      <div><h2>Measurement time</h2><time data-testid="measured" dateTime={reading?.recordedAt}>{reading?.recordedAt ?? 'Unavailable'}</time><p>{reading ? ageLabel(reading.recordedAt, now) : 'No measurement clock yet'}</p></div>
      <div><h2>Original server receipt</h2><time dateTime={snapshot?.receivedAt}>{snapshot?.receivedAt ?? 'Unavailable'}</time><p>{snapshot ? ageLabel(snapshot.receivedAt, now) : 'No server receipt yet'}</p></div>
      <p className="timing-note">A new receipt or healthy server stream does not make an old measurement fresh. Device heartbeat and buffering status are not available.</p>
    </section>
  </PageFrame>
}
