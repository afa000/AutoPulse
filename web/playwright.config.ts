import { defineConfig } from '@playwright/test'

if (process.env.AUTOPULSE_ISOLATED_DEMO !== 'true' || !process.env.AUTOPULSE_BACKEND_URL) {
  throw new Error('Run via Maven -Dtest=DashboardBrowserDemo so browser tests use disposable infrastructure')
}
export default defineConfig({
  testDir: './e2e', workers: 1, fullyParallel: false, timeout: 45_000,
  // Keep care screenshots across the other explicit demos, which clean test-results.
  outputDir: process.env.AUTOPULSE_CARE_MILESTONE === 'true' ? `./playwright-report/care-milestone/${process.env.AUTOPULSE_CARE_PHASE}`
    : process.env.AUTOPULSE_CARE_DEMO === 'true' ? './playwright-report/care-artifacts' : './test-results',
  testMatch: process.env.AUTOPULSE_CARE_MILESTONE === 'true' ? ['**/care-milestone.spec.ts']
    : process.env.AUTOPULSE_CARE_DEMO === 'true' ? ['**/care.spec.ts']
    : process.env.AUTOPULSE_NUMERIC_DEMO === 'true' ? ['**/numeric-boundary.spec.ts']
    : process.env.AUTOPULSE_TRIP_MILESTONE === 'true' ? ['**/trip-milestone.spec.ts']
    : process.env.AUTOPULSE_LIVE_DEMO === 'true' ? ['**/*.spec.ts']
      : ['**/dashboard.spec.ts', '**/trips.spec.ts'],
  testIgnore: process.env.AUTOPULSE_CARE_MILESTONE === 'true' ? [] : ['**/care-milestone.spec.ts', ...(process.env.AUTOPULSE_CARE_DEMO === 'true' ? [] : process.env.AUTOPULSE_NUMERIC_DEMO === 'true' ? ['**/care.spec.ts'] : process.env.AUTOPULSE_TRIP_MILESTONE === 'true' ? ['**/numeric-boundary.spec.ts', '**/care.spec.ts'] : ['**/trip-milestone.spec.ts', '**/numeric-boundary.spec.ts', '**/care.spec.ts'])],
  use: { baseURL: 'http://127.0.0.1:5174', browserName: 'chromium', trace: 'retain-on-failure' },
  reporter: 'list',
  webServer: { command: 'npm run dev -- --port 5174', url: 'http://127.0.0.1:5174', reuseExistingServer: false, timeout: 30_000 },
})
