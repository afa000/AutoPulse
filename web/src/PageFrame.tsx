import type { ReactNode } from 'react'

export function PageFrame({ page, children }: { page: 'live' | 'trips' | 'diagnostics' | 'maintenance' | null; children: ReactNode }) {
  return <main>
    <header className="masthead">
      <a className="brand" href="#/" aria-label="AutoPulse">Auto<span>Pulse</span><span className="brand-dot" aria-hidden="true" /></a>
      <nav className="page-nav" aria-label="Main navigation">
        <a href="#/" aria-current={page === 'live' ? 'page' : undefined}>Live</a>
        <a href="#/trips" aria-current={page === 'trips' ? 'page' : undefined}>Trips</a>
        <a href="#/diagnostics" aria-current={page === 'diagnostics' ? 'page' : undefined}>Diagnostics</a>
        <a href="#/maintenance" aria-current={page === 'maintenance' ? 'page' : undefined}>Maintenance</a>
      </nav>
      <span className="eyebrow">LOCAL TELEMETRY / NO ECU COMMANDS</span>
    </header>
    {children}
    <footer><strong>For parked use only.</strong> This dashboard is not intended for interaction while driving. Informational readings are not a diagnosis.</footer>
  </main>
}
