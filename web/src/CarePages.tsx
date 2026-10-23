import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { PageFrame } from './PageFrame'
import { detail, endpoint, event, integer, object, page, profile, revision, safeLink, text, time, wire, type CarePage, type ObjectData } from './care'
import { useCare, useCommand } from './useCare'

function Evidence({ value }: { value: unknown }) {
  if (Array.isArray(value)) return value.length ? <ol>{value.map((v, i) => <li key={i}><Evidence value={v} /></li>)}</ol> : <span>None reported</span>
  if (object(value)) return <dl className="evidence">{Object.entries(value).map(([key, v]) => <div key={key}><dt>{key.replace(/([a-z])([A-Z])/g, '$1 $2')}</dt><dd><Evidence value={v} /></dd></div>)}</dl>
  if (safeLink(value)) return <a href={safeLink(value)} target="_blank" rel="noreferrer">{text(value)}</a>
  return <span>{value === null || value === undefined ? 'Unknown / not supplied' : typeof value === 'string' ? value : wire(value)}</span>
}
function Status({ busy, error, retained, loadedAt }: { busy: boolean; error: string | null; retained: boolean; loadedAt: string | null }) {
  return <><p role="status" className="request-status">{busy ? 'Loading saved evidence…' : error ? 'Request unsuccessful.' : 'Saved evidence loaded. Refresh to check for changes.'}</p>
    {error && <p role="alert" className="notice">{error}</p>}
    {retained && <p className="notice">Retained last-known data, not a successful new response. Do not act on an outdated revision.</p>}
    {loadedAt && <p className="request-status">Browser fetched at {loadedAt}; this is not measurement time.</p>}</>
}
function Processing({ value }: { value: unknown }) {
  return <p className={value === 'CURRENT' ? 'request-status' : 'notice'}>Processing: {text(value)}. {value === 'CURRENT' ? 'Accepted work is caught up; this does not prove complete capture or vehicle health.' : 'Accepted evidence may not all be reflected here. Refresh after processing; workers are opt-in and disabled by default.'}</p>
}
function Freshness({ state, capturedAt }: { state: unknown; capturedAt: unknown }) {
  return <MeasurementAge key={text(capturedAt)} state={state} capturedAt={capturedAt} />
}
function MeasurementAge({ state, capturedAt }: { state: unknown; capturedAt: unknown }) {
  const [now, setNow] = useState(Date.now)
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 10_000); return () => clearInterval(timer) }, [])
  const age = time(capturedAt) ? now - Date.parse(capturedAt) : null
  const label = age !== null && age < 0 ? 'CLOCK_WARNING' : age !== null && age > 300_000 && ['FRESH', 'CURRENT'].includes(text(state)) ? 'STALE' : text(state)
  return <span>Freshness: {label} · captured {text(capturedAt)} (UTC). Browser refresh does not renew measurement time.</span>
}
function Mil({ value }: { value: unknown }) {
  const m = object(value) ? value : {}
  return <span>MIL: {m.outcome === 'COMPLETE' ? m.illuminated === true ? 'Reported on' : m.illuminated === false ? 'Reported off' : 'Unknown' : 'Unknown'} · read {text(m.outcome)}</span>
}
function Intro({ vehicle, title, children }: { vehicle: string; title: string; children?: React.ReactNode }) {
  return <section className="intro trip-intro"><div><p className="eyebrow">VEHICLE / {vehicle}</p><h1>{title}</h1><p className="subtitle">Observed evidence and informational advisories. Not a diagnosis, repair guarantee or safe-to-drive assessment. Summary times in UTC; raw evidence retains explicit offsets.</p></div>{children}</section>
}
function Pager({ data, busy, error, cursor, setCursor, refresh }: { data: CarePage | null; busy: boolean; error: string | null; cursor: string | null; setCursor: (s: string | null) => void; refresh: () => void }) {
  return <div className="trip-actions pagination"><button disabled={busy || !!error || !data?.nextCursor} onClick={() => setCursor(data?.nextCursor ?? null)}>Next page (older evidence)</button>
    <button disabled={busy} onClick={() => { setCursor(null); refresh() }}>Refresh newest</button><p>Up to 20 items per page. {cursor ? 'Viewing an older page.' : 'Newest page.'}</p></div>
}

export function Diagnostics({ vehicle }: { vehicle: string }) {
  const [cursor, setCursor] = useState<string | null>(null)
  const parse = useCallback((v: unknown) => page(v, 'diagnostics', vehicle), [vehicle])
  const state = useCare(`${endpoint(vehicle, 'diagnostics')}?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, parse)
  const latest = state.data?.latestSnapshot, s = object(latest) && object(latest.snapshot) ? latest.snapshot : null
  return <PageFrame page="diagnostics"><Intro vehicle={vehicle} title="Diagnostics. Evidence first." /><Status {...state} />
    {state.data && <><Processing value={state.data.processingState} />
      <section className="care-panel" aria-label="Latest diagnostic snapshot"><h2>Latest accepted capture · not all-device coverage</h2>
        {s && object(latest) ? <><p className="trip-source">{text(s.source)}{s.source === 'SIMULATED' && ' · synthetic evidence, not your vehicle condition'}</p>
          <p><Freshness state={latest.freshness} capturedAt={s.capturedAt} /></p><p>Device {text(s.deviceId)} · sequence {integer(s.diagnosticSequence)} · received {text(latest.receivedAt)} (UTC)</p>
          {Array.isArray(s.ecus) && s.ecus.map((e, i) => object(e) && <article className="scope" key={i}><h3>ECU {text(e.ecuId)} · overall {text(e.outcome)}</h3><p><Mil value={e.mil} /></p>
            <ul>{Array.isArray(e.categories) && e.categories.map((c, j) => object(c) && <li key={j}>{text(c.category)} · {text(c.outcome)}: {Array.isArray(c.codes) && c.codes.length ? c.codes.join(', ') : c.outcome === 'COMPLETE' ? e.outcome === 'COMPLETE' ? 'Complete empty read for this scope only; not proof of repair or vehicle health.' : 'Complete empty read for this category only; other ECU reads are incomplete. Not all-scope coverage, proof of repair or vehicle health.' : 'No complete absence evidence. Unsupported, partial or failed reads are not empty success.'}</li>)}</ul></article>)}</>
          : <p>No diagnostic snapshots accepted. MIL and code availability are unknown; no faults or healthy baseline can be inferred.</p>}</section>
      <section aria-label="Diagnostic events">{state.data.items.length === 0 && <p className="empty">No projected diagnostic events on this page. Consult capture outcomes and processing above; this is not a health assessment.</p>}
        <ul className="care-list">{state.data.items.map(e => <li className="care-panel" key={text(e.eventId)}><DiagnosticEvent value={e} /><a href={`#/diagnostics/${text(e.eventId)}`}>View evidence for {text(e.code)} · {text(e.category)} · {text(e.ecuId)}</a></li>)}</ul></section></>}
    <Pager {...state} cursor={cursor} setCursor={setCursor} />
  </PageFrame>
}
function DiagnosticEvent({ value: e }: { value: ObjectData }) {
  const d = object(e.definition) ? e.definition : {}
  return <><h2>{text(e.code)} · {text(e.category)}</h2><p className="trip-source">{text(e.source)}{e.source === 'SIMULATED' && ' · synthetic diagnostic observation'}</p>
    <p>{text(e.ecuId)} / {text(e.deviceId)} · {text(e.observationState)} · current scope read {text(e.currentReadOutcome)}</p>
    <p><Mil value={e.mil} /></p><p><Freshness state={e.freshness} capturedAt={e.lastAppliedAt} /></p>
    <p>First observed {text(e.firstObservedAt)} · last positive {text(e.lastObservedAt)} (UTC)</p>
    <p>Cycle {integer(e.cycleNumber)} · positive observations in this cycle {integer(e.positiveObservationCount)}. Replays are not new observations.</p>
    <p>{e.observationState === 'NOT_OBSERVED' ? 'Not observed in a qualifying complete read. This does not prove repair or ECU clearing.' : 'Observed code, not proof of a failed component. A newer incomplete read cannot resolve this observation.'}</p>
    <p>Definition: {text(d.verification)} · {typeof d.description === 'string' ? d.description : 'Unknown definition. No unverified component description is supplied.'}</p>
    <details><summary>Definition provenance</summary><Evidence value={d} /></details></>
}
export function DiagnosticDetail({ vehicle, id }: { vehicle: string; id: string }) {
  const parse = useCallback((v: unknown) => event(v, vehicle) && v.eventId === id && Array.isArray(v.recentTransitions) && typeof v.hasEarlierTransitions === 'boolean' ? v : null, [vehicle, id])
  const state = useCare(endpoint(vehicle, `diagnostics/${id}`), parse)
  return <PageFrame page="diagnostics"><Intro vehicle={vehicle} title="Diagnostic history." /><a href="#/diagnostics">← Back to Diagnostics</a><button onClick={state.refresh} disabled={state.busy}>Refresh event</button><Status {...state} />
    {state.data && <section className="care-panel"><Processing value={state.processing} /><DiagnosticEvent value={state.data} /><h3>Snapshot references</h3><Evidence value={state.data.evidence} />
      <h3>Recent transitions · newest first, at most 100</h3><Evidence value={state.data.recentTransitions} />
      {state.data.hasEarlierTransitions === true && <p className="notice">Earlier transitions exist but are not included in this bounded view.</p>}</section>}</PageFrame>
}

function CommandStatus({ command }: { command: ReturnType<typeof useCommand> }) {
  return <><p role="status" className="request-status">{command.busy ? 'Saving…' : command.message}</p>
    {command.pending && !command.busy && <div className="notice"><p>An unconfirmed submission is saved in this tab. It may already exist on the server. Fields are locked until the identical retry returns a receipt or a definite rejection.</p><button onClick={command.retry}>Retry identical submission</button></div>}</>
}
function fields(event: FormEvent<HTMLFormElement>) {
  event.preventDefault()
  const data = new FormData(event.currentTarget)
  return (key: string) => String(data.get(key) ?? '')
}
function ProfileForm({ vehicle, data, saved, disabled }: { vehicle: string; data: ObjectData; saved: () => void; disabled: boolean }) {
  const p = object(data.profile) ? data.profile : {}
  const command = useCommand(endpoint(vehicle, 'service-profile'), saved)
  return <section className="care-panel"><h2>Manual service profile · revision {integer(data.revision)}</h2><p>No schedule is selected for you. Confirm applicability, normal/severe usage and oil requirements against the manual before relying on a reminder.</p>
    <form onSubmit={e => { const get = fields(e); void command.submit('PUT', { expectedRevision: revision(data.revision), applicability: get('applicability'), usageSchedule: get('usageSchedule'), distanceUnit: get('distanceUnit'), timeZone: get('timeZone'), normalOilSpecificationConfirmed: get('oilConfirmed') === 'on' }) }}>
      <fieldset className="care-form" disabled={disabled || command.busy || command.pending !== null}><legend>Schedule inputs</legend>
        <label>Vehicle applicability<select name="applicability" defaultValue={typeof p.applicability === 'string' ? p.applicability : 'UNCONFIRMED'}><option value="UNCONFIRMED">Unconfirmed</option><option value="US_2021_VENUE_G16">U.S. 2021 Hyundai Venue G1.6</option></select></label>
        <label>Usage schedule<select name="usageSchedule" defaultValue={typeof p.usageSchedule === 'string' ? p.usageSchedule : 'UNSET'}><option value="UNSET">Unset · unknown</option><option value="NORMAL">Normal (explicit selection)</option><option value="SEVERE">Severe (explicit selection)</option></select></label>
        <label>Schedule distance unit<select name="distanceUnit" defaultValue={typeof p.distanceUnit === 'string' ? p.distanceUnit : 'MI'}><option value="MI">Miles</option><option value="KM">Kilometres</option></select></label>
        <label>Calendar time zone (IANA)<input name="timeZone" required maxLength={80} placeholder="America/New_York" defaultValue={typeof p.timeZone === 'string' ? p.timeZone : ''} /></label>
        <label className="check"><input type="checkbox" name="oilConfirmed" defaultChecked={p.normalOilSpecificationConfirmed === true} />I have confirmed the applicable normal-schedule oil specification.</label>
        <button>Save profile</button></fieldset></form><CommandStatus command={command} /></section>
}
function OilAssessment({ value }: { value: unknown }) {
  const a = object(value) ? value : {}
  return <section className="care-panel"><h2>Oil and filter schedule · {text(a.state)}</h2><p>MANUAL inputs only · not estimated trip distance. Unknown history, unset applicability or stale odometer readings cannot establish that service is up to date.</p>
    <p>Reason: {text(a.reason)} · odometer freshness: {text(a.odometerFreshness)}</p>
    <p>Applicable schedule: {object(a.profile) ? text(a.profile.usageSchedule) : 'UNSET'} · evaluated {text(a.evaluatedAt)} (UTC); refresh for a new calendar evaluation.</p>
    {a.dueDate !== undefined && <p>Next calendar boundary: {text(a.dueDate)} · odometer boundary: {text(a.dueOdometerValue)} {text(a.odometerUnit)} · whichever first.</p>}
    <details><summary>Schedule provenance and exact manual input basis</summary><Evidence value={a} /></details></section>
}
function EntryForm({ vehicle, initial, saved, disabled }: { vehicle: string; initial?: ObjectData; saved: () => void; disabled: boolean }) {
  const command = useCommand(endpoint(vehicle, initial ? `maintenance-records/${text(initial.entryId)}` : 'maintenance-records'), saved)
  const [validation, setValidation] = useState('')
  return <section className="care-panel"><h2>{initial ? `Correct manual record · revision ${integer(initial.revision)}` : 'Record service or odometer'}</h2>
    <p>Enter actual manual readings, never a trip-distance estimate. Oil service means a completed oil and filter service. Corrections append a revision; they do not erase history.</p>
    <form onSubmit={e => { const get = fields(e), at = get('observedAt'); if (!time(at) || Date.parse(at) > Date.now()) { setValidation('Use a valid UTC timestamp ending in Z, with at most six fractional digits, not in the future.'); return }
      setValidation(''); void command.submit(initial ? 'PATCH' : 'POST', { expectedRevision: revision(initial?.revision), kind: get('kind'), serviceType: get('kind') === 'SERVICE' ? 'OIL_FILTER' : null, observedAt: at, odometerValue: get('odometerValue'), odometerUnit: get('odometerUnit'), notes: get('notes').trim() || null, correctionReason: initial ? get('correctionReason').trim() : null }) }}>
      <fieldset className="care-form" disabled={disabled || command.busy || command.pending !== null}><legend>{initial ? 'Replacement values' : 'Manual observation'}</legend>
        {initial ? <><input type="hidden" name="kind" value={text(initial.kind)} /><p>Record kind: {text(initial.kind)} (cannot change)</p></> : <label>Record kind<select name="kind"><option value="ODOMETER">Manual odometer</option><option value="SERVICE">Completed oil and filter service</option></select></label>}
        <label>Observed at (UTC)<input name="observedAt" required placeholder="2026-10-23T12:00:00Z" defaultValue={initial ? text(initial.observedAt) : ''} aria-describedby="utc-help" /></label>
        <p id="utc-help">Use the measurement/service time, not the submission time. Example: 2026-10-23T12:00:00Z.</p>
        <label>Actual odometer value<input name="odometerValue" inputMode="decimal" required pattern="(0|[1-9][0-9]{0,6})(\.[0-9]{1,3})?" placeholder="18000.125" defaultValue={initial ? text(initial.odometerValue) : ''} aria-describedby="odo-help" /></label>
        <p id="odo-help">0–9999999, up to three decimal places; no commas. Existing earlier/later readings must remain consistent.</p>
        <label>Odometer unit<select name="odometerUnit" defaultValue={initial ? text(initial.odometerUnit) : 'MI'}><option value="MI">Miles</option><option value="KM">Kilometres</option></select></label>
        <label>Notes (optional)<textarea name="notes" maxLength={500} defaultValue={initial?.notes === null || !initial ? '' : text(initial.notes)} /></label>
        {initial && <label>Correction reason<input name="correctionReason" required maxLength={200} /></label>}
        <button>{initial ? 'Save correction' : 'Save manual record'}</button></fieldset></form>
    {validation && <p role="alert" className="notice">{validation}</p>}<CommandStatus command={command} /></section>
}

export function Maintenance({ vehicle }: { vehicle: string }) {
  const [epoch, setEpoch] = useState(0), [cursor, setCursor] = useState<string | null>(null)
  // A successful write remounts reads/forms together so no stale optimistic revision is submitted.
  return <PageFrame page="maintenance"><Intro vehicle={vehicle} title="Maintenance. Context, not certainty." />
    <MaintenanceContent key={`${vehicle}/${epoch}`} vehicle={vehicle} cursor={cursor} setCursor={setCursor} saved={() => { setCursor(null); setEpoch(e => e + 1) }} />
  </PageFrame>
}
function MaintenanceContent({ vehicle, cursor, setCursor, saved }: { vehicle: string; cursor: string | null; setCursor: (s: string | null) => void; saved: () => void }) {
  const p = useCare(endpoint(vehicle, 'service-profile'), profile)
  const parse = useCallback((v: unknown) => page(v, 'maintenance-records', vehicle), [vehicle])
  const records = useCare(`${endpoint(vehicle, 'maintenance-records')}?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, parse)
  return <><div className="trip-actions"><button onClick={saved} disabled={p.busy || records.busy}>Refresh maintenance</button><a href="#/recommendations">View recommendations and actions</a></div><Status {...p} />
    {p.data && <><OilAssessment value={p.data.oilAssessment} /><ProfileForm key={p.loadedAt} vehicle={vehicle} data={p.data} saved={saved} disabled={p.busy || !!p.error} /></>}
    <EntryForm vehicle={vehicle} saved={saved} disabled={p.busy || !!p.error} />
    <section aria-label="Manual record history"><h2>Manual service and odometer history</h2><Status {...records} />
      {records.data && <><Processing value={records.data.processingState} />{records.data.items.length === 0 && <p className="empty">No manual records on this page. Missing history is unknown, not zero mileage or completed maintenance.</p>}
        <ul className="care-list">{records.data.items.map(e => <li className="care-panel" key={text(e.entryId)}><h3>{text(e.kind) === 'SERVICE' ? 'Oil and filter service' : 'Manual odometer'}</h3><p>{text(e.odometerValue)} {text(e.odometerUnit)} · observed {text(e.observedAt)} (UTC) · revision {integer(e.revision)}</p><a href={`#/maintenance/${text(e.entryId)}`}>View history or correct record {text(e.entryId)}</a></li>)}</ul></>}
      <Pager {...records} cursor={cursor} setCursor={setCursor} /></section></>
}
export function MaintenanceDetail({ vehicle, id }: { vehicle: string; id: string }) {
  const [epoch, setEpoch] = useState(0)
  return <PageFrame page="maintenance"><Intro vehicle={vehicle} title="Manual record history." /><a href="#/maintenance">← Back to Maintenance</a><RecordDetail key={`${vehicle}/${id}/${epoch}`} vehicle={vehicle} id={id} saved={() => setEpoch(n => n + 1)} /></PageFrame>
}
function RecordDetail({ vehicle, id, saved }: { vehicle: string; id: string; saved: () => void }) {
  const parse = useCallback((v: unknown) => detail(v, 'maintenance-records', id), [id])
  const state = useCare(endpoint(vehicle, `maintenance-records/${id}`), parse)
  return <><button onClick={state.refresh} disabled={state.busy}>Refresh record</button><Status {...state} />
    {state.data && object(state.data.item) && <><details className="care-panel" open><summary>Recent revisions · newest first, at most 100</summary><Evidence value={state.data.recentRevisions} />{state.data.hasEarlierRevisions === true && <p>Earlier revisions exist and are not included here.</p>}</details>
      <EntryForm key={state.loadedAt} vehicle={vehicle} initial={state.data.item} saved={saved} disabled={state.busy || !!state.error} /></>}</>
}
export function Recommendations({ vehicle }: { vehicle: string }) {
  const [cursor, setCursor] = useState<string | null>(null)
  const parse = useCallback((v: unknown) => page(v, 'recommendations', vehicle), [vehicle])
  const state = useCare(`${endpoint(vehicle, 'recommendations')}?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, parse)
  return <PageFrame page="maintenance"><Intro vehicle={vehicle} title="Advisories. Inspect the basis." /><a href="#/maintenance">← Back to Maintenance</a><Status {...state} />
    <p>Workflow actions do not clear codes, reset an ECU, certify a repair or establish driving safety. Expired means the rule no longer applies, not necessarily repaired.</p>
    {state.data && <><Processing value={state.data.processingState} /><OilAssessment value={state.data.oilAssessment} />
      {state.data.items.length === 0 && <p className="empty">No recommendations on this page. Missing inputs, incomplete capture or disabled processing are not proof that no service is needed.</p>}
      <ul className="care-list">{state.data.items.map(r => <li className="care-panel" key={text(r.id)}><Recommendation value={r} /><a href={`#/recommendations/${text(r.id)}`}>View evidence and actions for {text(r.kind)} · {text(r.id)}</a></li>)}</ul></>}
    <Pager {...state} cursor={cursor} setCursor={setCursor} /></PageFrame>
}
function Recommendation({ value: r }: { value: ObjectData }) {
  const observation = object(r.currentObservation) ? r.currentObservation : {}, evidence = object(r.evidence) ? r.evidence : {}
  return <><h2>{text(r.kind)} · {text(r.state)}</h2><p className="trip-source">{r.kind === 'TEMPERATURE' ? 'SIMULATED · synthetic temperature rule, not a manufacturer specification' : evidence.source === 'SIMULATED' || observation.source === 'SIMULATED' ? 'SIMULATED · synthetic diagnostic evidence, not your vehicle condition' : r.kind === 'OIL_FILTER' ? 'MANUAL · schedule reminder, not estimated distance' : `Source: ${text(evidence.source)} · uncertain evidence is not a diagnosis`}</p>
    <p>Rule {text(r.ruleVersion)} · revision {integer(r.revision)} · generated {text(r.generatedAt)} (UTC)</p>
    {r.kind === 'OIL_FILTER' ? <OilAssessment value={observation.oilAssessment} /> : <p><Freshness state={observation.freshness} capturedAt={observation.capturedAt} /></p>}
    <p>{typeof evidence.explanation === 'string' ? evidence.explanation : 'Informational advisory only. Inspect the evidence and applicability; do not infer component failure, repair or safety.'}</p></>
}
export function RecommendationDetail({ vehicle, id }: { vehicle: string; id: string }) {
  const [epoch, setEpoch] = useState(0)
  return <PageFrame page="maintenance"><Intro vehicle={vehicle} title="Advisory evidence and actions." /><a href="#/recommendations">← Back to Recommendations</a><AdviceDetail key={`${vehicle}/${id}/${epoch}`} vehicle={vehicle} id={id} saved={() => setEpoch(n => n + 1)} /></PageFrame>
}
function AdviceDetail({ vehicle, id, saved }: { vehicle: string; id: string; saved: () => void }) {
  const parse = useCallback((v: unknown) => detail(v, 'recommendations', id), [id])
  const state = useCare(endpoint(vehicle, `recommendations/${id}`), parse)
  const command = useCommand(endpoint(vehicle, `recommendations/${id}`), saved)
  const r = state.data && object(state.data.item) ? state.data.item : null
  return <><button onClick={state.refresh} disabled={state.busy}>Refresh advisory</button><Status {...state} />
    {r && <section className="care-panel"><Recommendation value={r} /><h3>Current observation · separate from the frozen rule basis</h3><Evidence value={r.currentObservation} />
      <details><summary>Frozen rule evidence and provenance</summary><Evidence value={r.evidence} /></details>
      <details><summary>Recent advisory revisions · newest first, at most 100</summary><Evidence value={state.data?.recentRevisions} />{state.data?.hasEarlierRevisions === true && <p>Earlier revisions exist and are not included here.</p>}</details>
      <p>Acknowledging means reviewed; dismissing means a workflow choice, not a resolved condition. Completing an observational advisory records workflow only, never repair. Oil completion requires an actual matching service record.</p>
      <div className="trip-actions">{(r.state === 'OPEN' || r.state === 'ACKNOWLEDGED') && <>
        {r.state === 'OPEN' && <button disabled={state.busy || !!state.error || command.busy || !!command.pending} onClick={() => { void command.submit('PATCH', { expectedRevision: revision(r.revision), state: 'ACKNOWLEDGED' }) }}>Acknowledge advisory</button>}
        <button disabled={state.busy || !!state.error || command.busy || !!command.pending} onClick={() => { void command.submit('PATCH', { expectedRevision: revision(r.revision), state: 'DISMISSED' }) }}>Dismiss advisory (not repair)</button>
        {r.kind !== 'OIL_FILTER' && <button disabled={state.busy || !!state.error || command.busy || !!command.pending} onClick={() => { void command.submit('PATCH', { expectedRevision: revision(r.revision), state: 'COMPLETED' }) }}>Complete workflow (not repair)</button>}</>}
        {r.kind === 'OIL_FILTER' && <a href="#/maintenance">Record completed oil and filter service</a>}</div></section>}
    <CommandStatus command={command} /></>
}
