import { lazy, Suspense, useCallback, useState } from 'react'
import { PageFrame } from './PageFrame'
import { completionLabel, coverage, displayNumber, duration, estimatedDistance, parseTrip, parseTripPage, type Trip } from './trips'
import { useTripRequest } from './useTripRequest'
const TripCharts = lazy(() => import('./TripCharts'))

const endpoint = (vehicle: string) => `/api/v1/vehicles/${encodeURIComponent(vehicle)}/trips`
function Processing({ state }: { state: string }) {
  const message = state === 'PENDING' ? 'Trip processing is pending. Some accepted readings may not appear yet.'
    : state === 'REVIEW' ? 'Trip processing needs review. Stored results may not include all accepted readings.'
      : state === 'UNKNOWN' ? 'Processing status is unavailable; completeness is unknown.' : null
  return message ? <p className="notice">{message}</p> : null
}
function RequestStatus({ busy, error, retained, loadedAt }: { busy: boolean; error: string | null; retained: boolean; loadedAt: string | null }) {
  return <>
    <p role="status" className="request-status">{busy ? 'Loading saved trips…' : error ? 'Trip request unsuccessful.' : 'Saved data loaded. Refresh to check for changes.'}</p>
    {error && <p role="alert" className="notice">{error}</p>}
    {retained && <p className="notice">Showing retained last-known results, not a successful new response. They may have changed.</p>}
    {loadedAt && <p className="request-status">Last successful browser fetch (not measurement time): <time dateTime={loadedAt}>{loadedAt}</time></p>}
  </>
}
function Source({ trip }: { trip: Trip }) {
  return <span className="trip-source">{trip.source}{trip.source === 'SIMULATED' && <span className="source-note"> · synthetic data</span>}</span>
}
function Status({ trip }: { trip: Trip }) {
  return <><strong>{trip.status}</strong> · {trip.finalization}<p>{completionLabel(trip.completionReason)}</p></>
}

export function TripList({ vehicle }: { vehicle: string }) {
  const [cursor, setCursor] = useState<string | null>(null)
  const parse = useCallback((raw: unknown) => parseTripPage(raw, vehicle), [vehicle])
  const url = `${endpoint(vehicle)}?limit=20${cursor === null ? '' : `&cursor=${encodeURIComponent(cursor)}`}`
  const state = useTripRequest(url, parse)
  const newest = () => { setCursor(null); state.refresh() }
  return <PageFrame page="trips">
    <section className="intro trip-intro"><div><p className="eyebrow">VEHICLE / {vehicle}</p><h1>Saved drives.<br />Context included.</h1>
      <p className="subtitle">Persisted trips, measured boundaries and clearly estimated distance. Times shown in UTC.</p></div>
      <div className="trip-actions"><button onClick={newest} disabled={state.busy}>Refresh newest trips</button></div></section>
    <RequestStatus {...state} />
    <Processing state={state.processing} />
    <section aria-label="Saved trips" aria-busy={state.busy}>
      {state.data?.items.length === 0 && <div className="empty"><h2>No saved trips on this page</h2><p>Readings need to be processed into trips before they appear here. Processing is disabled by default; an empty list does not mean no raw telemetry exists.</p></div>}
      {state.data && state.data.items.length > 0 && <ul className="trip-list">{state.data.items.map(trip => <li key={trip.tripId} className="trip-card">
        <div><h2><a href={`#/trips/${trip.tripId}`}>Trip starting <time dateTime={trip.startedAt}>{trip.startedAt}</time></a></h2><Source trip={trip} /></div>
        <div><span className="eyebrow">OBSERVED DURATION</span><p>{duration(trip.distance.durationSeconds)}</p><small>{trip.status === 'ACTIVE' ? 'Through the last observation, not a running clock' : 'Waiting time excluded'}</small></div>
        <div><span className="eyebrow">ESTIMATED DISTANCE</span><p>{estimatedDistance(trip)}</p><small>{coverage(trip)}</small></div>
        <div className="trip-state"><Status trip={trip} /><small>Revision {trip.revision}{trip.quality.length > 0 ? ` · ${trip.quality.length} quality flag(s)` : ''}</small></div>
      </li>)}</ul>}
    </section>
    <div className="trip-actions pagination">
      <button disabled={state.busy || !!state.error || !state.data?.nextCursor} onClick={() => setCursor(state.data?.nextCursor ?? null)}>Next page (older trips)</button>
      {cursor !== null && <button onClick={newest} disabled={state.busy}>Back to newest trips</button>}
      <p>Up to 20 trips per page. {state.data?.nextCursor ? 'More trips are available.' : state.data && !state.retained ? 'No further page in this snapshot.' : ''}</p>
    </div>
    <p className="request-status">Provisional trips can change after late readings or reconciliation. These estimates are not the vehicle odometer.</p>
  </PageFrame>
}

const summaryMetrics = [['rpm', 'Engine speed', 'RPM'], ['speedKph', 'Road speed', 'km/h'],
  ['coolantTempC', 'Coolant temperature', '°C'], ['engineLoadPct', 'Engine load', '%']] as const
export function TripDetail({ vehicle, id }: { vehicle: string; id: string }) {
  const parse = useCallback((raw: unknown) => {
    const trip = parseTrip(raw, vehicle)
    return trip?.tripId.toLowerCase() === id.toLowerCase() ? trip : null
  }, [vehicle, id])
  const state = useTripRequest(`${endpoint(vehicle)}/${encodeURIComponent(id)}`, parse)
  const trip = state.data
  return <PageFrame page="trips">
    <section className="intro trip-intro"><div><a href="#/trips">← Back to Trips</a><p className="eyebrow">VEHICLE / {vehicle}</p><h1>One drive.<br />The evidence behind it.</h1><p className="subtitle">Saved trip summary · measurement times in UTC</p></div>
      <div className="trip-actions"><button onClick={state.refresh} disabled={state.busy}>Refresh trip</button></div></section>
    <RequestStatus {...state} /><Processing state={state.processing} />
    {trip && <section aria-label="Trip summary" aria-busy={state.busy}>
      <div className="status-bar"><p><Source trip={trip} /></p><div className="trip-state"><Status trip={trip} /></div></div>
      {trip.finalization === 'PROVISIONAL' && <p className="notice">Provisional result: late readings or reconciliation may change boundaries and summaries. Refresh to check the revision.</p>}
      {trip.status === 'ACTIVE' && <p className="notice">Active means the stored projection has not completed this trip. Duration stops at the last observation, not the browser clock.</p>}
      {trip.status === 'SUPERSEDED' && <div className="notice"><p>This trip was superseded. Its old summary is reference-only; use a replacement trip.</p>
        {trip.supersededBy.length > 0 ? <ul>{trip.supersededBy.map(replacement => <li key={replacement}><a href={`#/trips/${replacement}`}>Replacement trip {replacement}</a></li>)}</ul>
          : <p>No replacement link is available. Return to Trips and refresh.</p>}</div>}
      <dl className="trip-facts">
        <div><dt>Start measurement (UTC)</dt><dd><time dateTime={trip.startedAt}>{trip.startedAt}</time></dd></div>
        <div><dt>End measurement (UTC)</dt><dd>{trip.endedAt ? <time dateTime={trip.endedAt}>{trip.endedAt}</time> : 'Not completed'}</dd></div>
        <div><dt>Observed through (UTC)</dt><dd><time dateTime={trip.observedThrough}>{trip.observedThrough}</time></dd></div>
        <div><dt>Completion confirmed (UTC)</dt><dd>{trip.confirmedAt ? <time dateTime={trip.confirmedAt}>{trip.confirmedAt}</time> : 'Not confirmed'}</dd></div>
        <div><dt>Observed duration</dt><dd>{duration(trip.distance.durationSeconds)}</dd></div>
        <div><dt>Estimated distance · not odometer</dt><dd>{estimatedDistance(trip)}<small>{coverage(trip)}</small></dd></div>
        <div><dt>Revision / algorithm</dt><dd>{trip.revision} / {trip.algorithmVersion}</dd></div>
        <div><dt>Primary device / samples</dt><dd>{trip.primaryDeviceId} / {trip.sampleCount}</dd></div>
      </dl>
      <section aria-label="Sensor statistics" className="statistics">
        <h2>Sample-weighted sensor summaries</h2><p>Means are non-null sum/count, not time-weighted averages. Missing sensors stay unavailable. Values are rounded only for display.</p>
        <p className="table-scroll-hint">On narrow screens, scroll the table horizontally to see every column.</p>
        <div className="table-scroll" tabIndex={0} role="region" aria-label="Sensor statistics table, horizontally scrollable"><table><caption>Sensor statistics and non-null sample coverage</caption><thead><tr><th scope="col">Sensor</th><th scope="col">Mean</th><th scope="col">Minimum</th><th scope="col">Maximum</th><th scope="col">Samples present</th></tr></thead>
          <tbody>{summaryMetrics.map(([name, label, unit]) => { const metric = trip.metrics[name]; return <tr key={name}><th scope="row">{label} ({unit})</th>
            <td>{metric.sum === null ? 'Unavailable' : displayNumber(metric.sum / metric.count)}</td><td>{metric.min === null ? 'Unavailable' : displayNumber(metric.min)}</td><td>{metric.max === null ? 'Unavailable' : displayNumber(metric.max)}</td><td>{metric.count} / {trip.sampleCount}</td></tr> })}</tbody></table></div>
      </section>
      <section className="quality" aria-label="Quality and uncertainty"><h2>Quality and uncertainty</h2>
        {trip.quality.length > 0 ? <><p>Reported processing flags (not diagnostic alerts):</p><ul>{trip.quality.map(flag => <li key={flag}>{flag}</li>)}</ul></> : <p>No additional quality flags were reported. This does not certify complete capture or uninterrupted sensor coverage.</p>}
        <p>Estimated distance excludes unsupported speed intervals and does not bridge long gaps. A signal timeout is not proof of engine-off.</p>
      </section>
      {trip.status !== 'SUPERSEDED' && (state.generation ? <Suspense fallback={<p>Loading chart tools…</p>}>
        <TripCharts key={`${trip.tripId}/${trip.revision}/${state.generation}/${state.loadedAt}`} trip={trip} generation={state.generation} />
      </Suspense> : <p className="notice">Chart consistency token unavailable. Refresh this trip against an updated backend before loading history.</p>)}
    </section>}
  </PageFrame>
}
