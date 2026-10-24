import { useCallback, useState } from 'react'
import { chartMetrics, historyWindow, parseHistory, type Resolution } from './history'
import { HistoryPlot } from './HistoryPlot'
import { displayNumber, type Trip } from './trips'
import { RequestFailure, useTripRequest } from './useTripRequest'

export default function TripCharts({ trip, generation }: { trip: Trip; generation: string }) {
  const [resolution, setResolution] = useState<Resolution>(trip.distance.durationSeconds > 1800 ? '1m' : 'raw')
  const [limit, setLimit] = useState(200)
  return <section className="history" aria-label="Trip measurement charts">
    <h2>Trip measurement history</h2>
    <p>Showing bounded pages, not necessarily the full drive. Times are measurement times in UTC; units are metric. {trip.source} data only.</p>
    <div className="trip-actions history-controls">
      <label>History resolution <select value={resolution} onChange={event => setResolution(event.target.value as Resolution)}>
        <option value="raw">Raw readings</option><option value="1m">1-minute means</option><option value="5m">5-minute means</option>
      </select></label>
      <label>Points per history page <select value={limit} onChange={event => setLimit(Number(event.target.value))}><option value={50}>50</option><option value={200}>200</option></select></label>
    </div>
    <HistoryWindow key={`${resolution}/${limit}`} trip={trip} generation={generation} resolution={resolution} limit={limit} />
  </section>
}
function HistoryWindow({ trip, generation, resolution, limit }: { trip: Trip; generation: string; resolution: Resolution; limit: number }) {
  const [windowIndex, setWindowIndex] = useState(0)
  try {
    const range = historyWindow(trip, resolution, windowIndex)
    return <HistoryPageView key={windowIndex} trip={trip} generation={generation} resolution={resolution} limit={limit} range={range}
      nextWindow={range.more ? () => setWindowIndex(previous => previous + 1) : null} />
  } catch { return <p className="notice" role="alert">The trip boundaries cannot be represented safely by this history API. No chart request was sent.</p> }
}
function HistoryPageView({ trip, generation, resolution, limit, range, nextWindow }: { trip: Trip; generation: string; resolution: Resolution; limit: number;
  range: { from: string; to: string }; nextWindow: (() => void) | null }) {
  const [cursor, setCursor] = useState<string | null>(null)
  const parse = useCallback((raw: unknown) => {
    const page = parseHistory(raw, trip, range, resolution, limit)
    if (page && page.generation !== generation) throw new RequestFailure('History changed since this summary was fetched. Refresh the trip summary before viewing charts.')
    return page
  }, [trip, range, resolution, limit, generation])
  const parameters = new URLSearchParams({ from: range.from, to: range.to, tripId: trip.tripId, resolution, limit: String(limit) })
  if (cursor !== null) parameters.set('cursor', cursor)
  const state = useTripRequest(`/api/v1/vehicles/${encodeURIComponent(trip.vehicleId)}/telemetry/history?${parameters}`, parse, 'history')
  const data = state.data
  const restart = () => { setCursor(null); state.refresh() }
  return <>
    <p className="history-range">Requested window [<time>{range.from}</time>, <time>{range.to}</time>)</p>
    <p role="status">{state.busy ? 'Loading measurement history…' : state.error ? 'History request unsuccessful.' : 'History page loaded.'}</p>
    {state.error && <p role="alert" className="notice">{state.error}</p>}
    {state.retained && <p className="notice">Retained last-known history page, not a successful new response. Do not treat it as current.</p>}
    {data && <>
      {state.processing !== 'CURRENT' && <p className="notice">History processing state: {state.processing}. Accepted readings may still be pending or under review.</p>}
      {data.availability.incomplete && <p className="notice">History coverage is marked incomplete. These charts do not certify continuous capture.</p>}
        {data.availability.rawState !== 'AVAILABLE' && <p className="notice">Raw history is {data.availability.rawState === 'EXPIRED' ? 'expired' : 'partially expired'} in this window. Expired observations occur before {data.availability.rawUnavailableBefore}; protected readings may remain before that bound. Available resolutions: {data.availability.availableResolutions.join(', ') || 'none'}. Stored trip summaries are separate.</p>}
      {!data.availability.availableResolutions.includes(resolution) && <p className="notice">This resolution is unavailable. Choose one of the available resolutions above; no missing values were filled in.</p>}
      {data.rows.length === 0 ? <p className="empty">{data.availability.rawState !== 'AVAILABLE' ? 'No points available at this resolution in this window; this is not proof of no drive.' : 'No recorded history points in this window. This is not a complete-capture guarantee.'}</p> : <>
        <p>{data.rows.length} displayed {resolution === 'raw' ? 'raw readings' : `${resolution} aggregate buckets`}. {data.nextCursor ? 'More points are available; continue paging.' : 'No further page in this window.'}</p>
        <HistoryPlot page={data} />
        <details className="history-values"><summary>Measurement values and sample coverage (accessible table)</summary>
          <p>Raw values or sample-weighted bucket means; numbers rounded for display. Counts are non-null samples / bucket samples, not elapsed-time coverage. Aggregate markers do not reveal gaps within a bucket.</p>
          <p className="table-scroll-hint">Scroll horizontally to see every column.</p>
          <div className="table-scroll" role="region" tabIndex={0} aria-label="History values table, horizontally scrollable">
            <table><caption>Displayed history page · {resolution} · UTC measurement times</caption>
              <thead><tr><th scope="col">Measurement / bucket start (UTC)</th>{resolution !== 'raw' && <th scope="col">Bucket end (exclusive UTC)</th>}
                {chartMetrics.map(([name, title, unit]) => <th key={name} scope="col">{title} ({unit}) · present/total</th>)}<th scope="col">Context</th></tr></thead>
              <tbody>{data.rows.map((row, index) => <tr key={index}><th scope="row"><time>{row.time}</time></th>{resolution !== 'raw' && <td><time>{row.end}</time></td>}
                {chartMetrics.map(([name]) => <td key={name}>{row.outsideDisplayRange?.includes(name) ? 'Outside display range (exact total retained)' : row.values[name] === null ? 'Unavailable' : displayNumber(row.values[name])} ({row.counts[name]}/{row.sampleCount})</td>)}
                <td>{row.boot ? `Boot ${row.boot}` : row.partial ? 'Clipped partial bucket' : 'Full UTC bucket'}</td></tr>)}</tbody>
            </table>
          </div>
        </details>
        {resolution !== 'raw' && <details><summary>Exact aggregate totals (unrounded decimal evidence)</summary>
          {data.rows.map((row, index) => <pre key={index} style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{row.time}{'\n'}{JSON.stringify(row.exactTotals, null, 2)}</pre>)}
        </details>}
      </>}
    </>}
    <div className="trip-actions pagination">
      <button onClick={() => setCursor(data?.nextCursor ?? null)} disabled={state.busy || !!state.error || !data?.nextCursor}>Next history page</button>
      <button onClick={restart} disabled={state.busy}>Restart history window</button>
      {nextWindow && <button onClick={nextWindow} disabled={state.busy || !!state.error || !data || !!data.nextCursor}>Next time window</button>}
    </div>
    <p>Each page replaces the previous one. Pages and time windows are not joined by chart lines. Summary refresh resets history and verifies its generation again.</p>
  </>
}
