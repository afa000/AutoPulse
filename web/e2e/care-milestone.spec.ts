import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'

type Event = { eventId: string; category: string; code: string; observationState: string; positiveObservationCount: number; cycleNumber: number }
type Advisory = { id: string; kind: string; state: string; evidence: Record<string, unknown>; currentObservation: Record<string, unknown> }

test('actual Python diagnostic and maintenance lifecycle across the committed-work JVM boundary', async ({ page, request }, info) => {
  test.setTimeout(150_000); page.setDefaultTimeout(10_000)
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  const api = '/api/v1/vehicles/venue-2021'
  const get = async (path: string) => { const response = await request.get(path); expect(response.ok()).toBe(true); return response.json() }
  const events = async (): Promise<Event[]> => (await get(`${api}/diagnostics`)).items
  const advisories = async (): Promise<Advisory[]> => (await get(`${api}/recommendations`)).items
  const stage = async (step: string) => {
    writeFileSync(process.env.AUTOPULSE_CARE_CONTROL!, step)
    await expect.poll(() => existsSync(process.env.AUTOPULSE_CARE_CONTROL! + '.ack') ? readFileSync(process.env.AUTOPULSE_CARE_CONTROL! + '.ack', 'utf8') : '', { timeout: 30_000 }).toBe(step)
  }
  const shot = async (name: string) => {
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath(name), fullPage: true })
  }
  const refreshDiagnostics = async () => { await page.goto('/#/diagnostics'); await page.getByRole('button', { name: 'Refresh newest' }).click() }
  await page.goto('/#/diagnostics')
  if (process.env.AUTOPULSE_CARE_PHASE === 'baseline') {
    await expect(page.getByText(/Complete empty read for this scope only/)).toHaveCount(3)
    await expect(page.getByText(/MIL: Reported off/)).toBeVisible()
    await expect(page.getByText(/No projected diagnostic events/)).toBeVisible()
    await expect(page.getByText(/SIMULATED · synthetic evidence/)).toBeVisible()
    expect(await events()).toEqual([]); expect(await advisories()).toEqual([])
    await shot('simulator-baseline-desktop.png')
    await page.getByRole('link', { name: 'Maintenance', exact: true }).focus(); await page.keyboard.press('Enter')
    await expect(page.getByRole('heading', { name: 'Oil and filter schedule · UNKNOWN' })).toBeVisible()
    await expect(page.getByRole('combobox', { name: 'Usage schedule', exact: true })).toHaveValue('UNSET')
    await page.setViewportSize({ width: 390, height: 844 }); await shot('simulator-baseline-maintenance-narrow.png')
    expect(errors).toEqual([])
    console.log('CARE_BASELINE_UI actualPython=true completeEmpty=true milOff=true oilUnknown=true scheduleUnset=true axe=0')
    return
  }

  const initial = await events()
  expect(initial).toHaveLength(3)
  expect(initial.find(e => e.category === 'CONFIRMED')!.positiveObservationCount).toBe(2)
  expect(initial.find(e => e.category === 'PENDING')!.positiveObservationCount).toBe(3)
  expect((await advisories()).filter(r => r.kind === 'DIAGNOSTIC')).toHaveLength(1)
  let thermal = (await advisories()).find(r => r.kind === 'TEMPERATURE')!
  expect(thermal.state).toBe('OPEN'); expect(thermal.evidence.thresholdC).toBe('110')
  expect(thermal.evidence.sustainSeconds).toBe(30)
  await expect(page.getByText(/positive observations in this cycle 2/)).toHaveCount(2)
  await shot('recovered-work-desktop.png')

  await stage('uncertain'); await refreshDiagnostics()
  await expect(page.getByText(/PRESENT · current scope read TIMEOUT/)).toHaveCount(3)
  await expect(page.getByText(/MIL: Unknown/)).toHaveCount(4)
  expect((await events()).map(e => [e.eventId, e.positiveObservationCount, e.observationState])).toEqual(initial.map(e => [e.eventId, e.positiveObservationCount, e.observationState]))
  thermal = (await advisories()).find(r => r.kind === 'TEMPERATURE')!
  expect(thermal.state).toBe('OPEN'); expect(thermal.currentObservation.activeCondition).toBe(true)
  expect(thermal.currentObservation.freshness).toBe('UNKNOWN')
  await page.goto(`/#/recommendations/${thermal.id}`)
  await expect(page.getByRole('heading', { name: 'TEMPERATURE · OPEN' })).toBeVisible()
  await expect(page.getByText(/SIMULATED · synthetic temperature rule/)).toBeVisible()
  await expect(page.getByText(/Freshness: UNKNOWN/)).toBeVisible()
  await page.getByText('Frozen rule evidence and provenance', { exact: true }).click()
  await expect(page.locator('details').filter({ has: page.getByText('Frozen rule evidence and provenance', { exact: true }) }).getByText('110', { exact: true })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 }); await shot('null-and-stale-control-narrow.png')

  await stage('absence'); await refreshDiagnostics()
  await expect(page.getByText(/Not observed in a qualifying complete read/)).toHaveCount(3)
  expect((await events()).every(e => e.observationState === 'NOT_OBSERVED')).toBe(true)
  expect((await advisories()).filter(r => r.kind === 'DIAGNOSTIC' && r.state === 'EXPIRED')).toHaveLength(1)
  expect((await advisories()).find(r => r.kind === 'TEMPERATURE')!.state).toBe('OPEN')
  await stage('recurrence'); await refreshDiagnostics()
  const recurring = await events()
  expect(recurring).toHaveLength(5)
  expect(recurring.find(e => e.code === 'P0117' && e.category === 'CONFIRMED' && e.cycleNumber === 2)!.positiveObservationCount).toBe(2)
  expect(recurring.find(e => e.code === 'P1999')!.positiveObservationCount).toBe(2)
  await expect(page.getByText(/Unknown definition/)).toBeVisible()
  await expect(page.getByText(/Cycle 2 · positive observations in this cycle 2/)).toBeVisible()
  await shot('genuine-recurrence-narrow.png')
  const other = await get('/api/v1/vehicles/empty-vehicle/diagnostics')
  expect(other.items).toEqual([]); expect(other.latestSnapshot).toBeNull()
  expect((await get('/api/v1/vehicles/empty-vehicle/recommendations')).items).toEqual([])
  expect((await request.get(`/api/v1/vehicles/empty-vehicle/diagnostics/${recurring[0].eventId}`)).status()).toBe(404)

  await stage('first-low')
  thermal = (await advisories()).find(r => r.kind === 'TEMPERATURE')!
  expect(thermal.state).toBe('OPEN'); expect(thermal.currentObservation.activeCondition).toBe(true)
  await stage('recovery')
  thermal = (await advisories()).find(r => r.kind === 'TEMPERATURE')!
  expect(thermal.state).toBe('EXPIRED'); expect(thermal.currentObservation.activeCondition).toBe(false)
  await page.goto(`/#/recommendations/${thermal.id}`)
  await expect(page.getByRole('heading', { name: 'TEMPERATURE · EXPIRED' })).toBeVisible()
  await expect(page.getByText(/SIMULATED · synthetic temperature rule/)).toBeVisible()
  await page.setViewportSize({ width: 1280, height: 900 }); await shot('qualified-recovery-desktop.png')

  await page.getByRole('link', { name: 'Maintenance', exact: true }).click()
  await expect(page.getByRole('heading', { name: /Manual service profile/ })).toBeVisible()
  await page.getByRole('combobox', { name: 'Vehicle applicability', exact: true }).selectOption('US_2021_VENUE_G16')
  await page.getByRole('combobox', { name: 'Usage schedule', exact: true }).selectOption('SEVERE')
  await page.getByLabel('Calendar time zone (IANA)').fill('America/New_York')
  await page.getByRole('button', { name: 'Save profile', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Manual service profile · revision 1' })).toBeVisible()
  const record = async (kind: string, at: string, value: string) => {
    await page.getByRole('combobox', { name: 'Record kind', exact: true }).selectOption(kind)
    await page.getByLabel('Observed at (UTC)').fill(at); await page.getByLabel('Actual odometer value').fill(value)
    await page.getByRole('button', { name: 'Save manual record', exact: true }).click()
  }
  await record('SERVICE', new Date(Date.now() - 40 * 86400_000).toISOString(), '10000')
  await expect.poll(async () => (await get(`${api}/maintenance-records`)).items.length).toBe(1)
  await expect(page.getByRole('button', { name: 'Save manual record', exact: true })).toBeEnabled()
  await record('ODOMETER', new Date(Date.now() - 2000).toISOString(), '15000')
  await expect(page.getByRole('heading', { name: 'Oil and filter schedule · DUE' })).toBeVisible()
  await stage('manual')
  const beforeService = await events(), beforeAdvisories = await advisories()
  const oil = beforeAdvisories.find(r => r.kind === 'OIL_FILTER')!
  expect(oil.state).toBe('OPEN')
  const bodies: string[] = []
  await page.route('**/maintenance-records', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    bodies.push(route.request().postData()!); expect((await route.fetch()).ok()).toBe(true)
    await route.abort('connectionfailed'); await page.unroute('**/maintenance-records')
  })
  await record('SERVICE', new Date(Date.now() - 500).toISOString(), '15000')
  await expect(page.getByRole('button', { name: 'Retry identical submission' })).toBeVisible()
  await page.reload()
  page.on('request', r => { if (r.method() === 'POST' && r.url().endsWith('/maintenance-records')) bodies.push(r.postData()!) })
  await page.getByRole('button', { name: 'Retry identical submission' }).click()
  await expect(page.getByRole('button', { name: 'Retry identical submission' })).toHaveCount(0)
  expect(bodies).toHaveLength(2); expect(bodies[0]).toBe(bodies[1])
  expect((await get(`${api}/maintenance-records`)).items).toHaveLength(3)
  await stage('service')
  expect((await get(`${api}/recommendations/${oil.id}`)).item.state).toBe('COMPLETED')
  expect(await events()).toEqual(beforeService)
  const afterAdvisories = await advisories()
  expect(afterAdvisories.filter(r => r.kind !== 'OIL_FILTER').map(r => [r.id, r.state])).toEqual(beforeAdvisories.filter(r => r.kind !== 'OIL_FILTER').map(r => [r.id, r.state]))
  await page.goto(`/#/recommendations/${oil.id}`)
  await expect(page.getByRole('heading', { name: 'OIL_FILTER · COMPLETED' })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 }); await shot('matching-only-service-narrow.png')
  expect(errors).toEqual([])
  console.log('CARE_MILESTONE_UI recoveredJvmWork=true repeatCounts=3,2,3,2,2 uncertainty+lateNonResolution=true completeAbsence+recurrence=true null+stale+gapNonRecovery=true qualifiedRecovery=true manualRetryDeduplicated=true oilOnlyCompletion=true isolation=true simulated=true axe=0')
})
