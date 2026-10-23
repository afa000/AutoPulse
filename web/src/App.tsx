import { useSyncExternalStore } from 'react'
import LiveDashboard from './LiveDashboard'
import { PageFrame } from './PageFrame'
import { TripDetail, TripList } from './TripPages'
import { Diagnostics, DiagnosticDetail, Maintenance, MaintenanceDetail, Recommendations, RecommendationDetail } from './CarePages'
import { uuid } from './trips'
import type { StreamFactory } from './useLiveTelemetry'

const configuredVehicle = import.meta.env.VITE_VEHICLE_ID || 'venue-2021'
const subscribe = (change: () => void) => {
  window.addEventListener('hashchange', change)
  return () => window.removeEventListener('hashchange', change)
}
const location = () => window.location.hash

export default function App({ vehicle = configuredVehicle, staleSeconds, factory }: {
  vehicle?: string; staleSeconds?: number; factory?: StreamFactory
}) {
  const hash = useSyncExternalStore(subscribe, location)
  if (hash === '' || hash === '#' || hash === '#/') return <LiveDashboard vehicle={vehicle} staleSeconds={staleSeconds} factory={factory} />
  if (hash === '#/trips') return <TripList key={vehicle} vehicle={vehicle} />
  if (hash === '#/diagnostics') return <Diagnostics key={vehicle} vehicle={vehicle} />
  if (hash === '#/maintenance') return <Maintenance key={vehicle} vehicle={vehicle} />
  if (hash === '#/recommendations') return <Recommendations key={vehicle} vehicle={vehicle} />
  for (const [route, Component] of [['diagnostics', DiagnosticDetail], ['maintenance', MaintenanceDetail], ['recommendations', RecommendationDetail]] as const) {
    const careId = hash.startsWith(`#/${route}/`) ? hash.slice(route.length + 3) : ''
    if (uuid(careId)) return <Component key={`${vehicle}/${careId}`} vehicle={vehicle} id={careId} />
  }
  const id = hash.startsWith('#/trips/') ? hash.slice(8) : ''
  if (uuid(id)) return <TripDetail key={`${vehicle}/${id}`} vehicle={vehicle} id={id} />
  return <PageFrame page={null}><section className="empty"><h1>Page not found</h1><p>Choose a page from the main navigation to continue.</p></section></PageFrame>
}
