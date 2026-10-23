import type { ReactNode } from 'react'

export function PageFrame({ page, children }: { page: 'live' | 'trips' | null; children: ReactNode }) {
  return <main>
    <header className="masthead">
      <a className="brand" href="#/" aria-label="AutoPulse">Auto<span>Pulse</span><span className="brand-dot" aria-hidden="true" /></a>
      <nav className="page-nav" aria-label="Main navigation">
        <a href="#/" aria-current={page === 'live' ? 'page' : undefined}>Live</a>
        <a href="#/trips" aria-current={page === 'trips' ? 'page' : undefined}>Trips</a>
      </nav>
      <span className="eyebrow">LOCAL TELEMETRY / READ ONLY</span>
    </header>
    {children}
    <footer><strong>For parked use only.</strong> This dashboard is not intended for interaction while driving. Informational readings are not a diagnosis.</footer>
  </main>
}
