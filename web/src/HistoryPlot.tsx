import { CartesianGrid, Line, LineChart, XAxis, YAxis } from 'recharts'
import { chartMetrics, micros, plotRows, utc, type HistoryPage } from './history'

export function HistoryPlot({ page }: { page: HistoryPage }) {
  const points = plotRows(page), origin = micros(page.from)
  const tick = (seconds: number) => {
    try { return utc(origin + BigInt(Math.round(seconds * 1_000_000))).slice(11, 19) } catch { return 'UTC' }
  }
  return <div className="history-plots">{chartMetrics.map(([name, title, unit]) => {
    const present = page.rows.filter(row => row.values[name] !== null).length
    return <figure className="history-plot" key={name}>
      <figcaption>{title} ({unit}) · {page.resolution === 'raw' ? 'readings' : `${page.resolution} bucket means`}</figcaption>
      <p>{present} / {page.rows.length} displayed points have this sensor. Measurement timestamps, displayed values and counts are in the table below.</p>
      {present === 0 ? <p className="empty">Sensor unavailable on this page. No zero values were invented.</p> : <div aria-hidden="true">
        <LineChart responsive style={{ width: '100%', height: 220 }} data={points} accessibilityLayer={false} margin={{ top: 15, right: 20, bottom: 10, left: 0 }}>
          <CartesianGrid stroke="#d9e0d9" strokeDasharray="3 3" />
          <XAxis dataKey="x" type="number" domain={['dataMin', 'dataMax']} tickFormatter={tick} tick={{ fontSize: 10 }} minTickGap={35} />
          <YAxis width={55} tick={{ fontSize: 10 }} domain={['auto', 'auto']} />
          <Line dataKey={name} type="linear" stroke={page.resolution === 'raw' ? '#277d72' : 'none'} strokeWidth={2} connectNulls={false}
            dot={{ r: 3, fill: '#277d72', stroke: '#142b31', strokeWidth: 1 }} isAnimationActive={false} />
        </LineChart>
      </div>}
      <p>Measurement time (UTC){page.resolution !== 'raw' ? ' · marker at bucket start; no interpolation between means' : ' · lines break at nulls, boot changes, ties, and gaps over 5 seconds'}</p>
    </figure>
  })}</div>
}
