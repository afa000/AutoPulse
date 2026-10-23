import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import examples from '../../contracts/examples/trip-api-examples.json' with { type: 'json' }

// Browser UI/contract fixtures, not a simulator-to-projected-trip end-to-end claim.
test('trip navigation, deep links, UTC summaries and keyboard/narrow layouts are accessible', async ({ page }, info) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route(/\/api\/v1\/vehicles\/venue-2021\/trips(?:[/?]|$)/, async route => {
    const path = new URL(route.request().url()).pathname
    await route.fulfill({ json: path.endsWith('/trips') ? examples.list : examples.detail,
      headers: { 'X-Trip-Processing-State': 'PENDING' } })
  })
  await page.goto('/#/trips')
  await expect(page.getByRole('heading', { name: /Saved drives/ })).toBeVisible()
  await expect(page.getByText(/Trip processing is pending/)).toBeVisible()
  await expect(page.getByText('0.04 km')).toBeVisible()
  await expect(page.getByRole('link', { name: 'Trips', exact: true })).toHaveAttribute('aria-current', 'page')
  await page.screenshot({ path: info.outputPath('trip-list-desktop.png'), fullPage: true })
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  // Native anchors are keyboard-operable, and hash navigation participates in Back.
  const open = page.getByRole('link', { name: /Trip starting/ })
  await open.focus(); await page.keyboard.press('Enter')
  await expect(page).toHaveURL(new RegExp(`#/trips/${examples.detail.tripId}$`))
  await expect(page.getByText('1 / trip-v1-draft')).toBeVisible()
  await expect(page.getByText(/synthetic data/)).toBeVisible()
  await expect(page.getByRole('table', { name: 'Sensor statistics and non-null sample coverage' })).toBeVisible()
  await expect(page.getByText(/For parked use only/)).toBeVisible()
  await page.screenshot({ path: info.outputPath('trip-detail-desktop.png'), fullPage: true })
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  await page.goBack(); await expect(page.getByRole('heading', { name: /Saved drives/ })).toBeVisible()
  await page.goto(`/#/trips/${examples.detail.tripId}`)
  await expect(page.getByText('1 / trip-v1-draft')).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 390)
  const tableRegion = page.getByRole('region', { name: 'Sensor statistics table, horizontally scrollable' })
  await tableRegion.focus(); await page.keyboard.press('ArrowRight')
  await expect.poll(() => tableRegion.evaluate(element => element.scrollLeft)).toBeGreaterThan(0)
  await tableRegion.evaluate(element => { element.scrollLeft = 0 })
  await page.screenshot({ path: info.outputPath('trip-detail-narrow.png'), fullPage: true })
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  await page.getByRole('link', { name: '← Back to Trips' }).click()
  await expect(open).toBeVisible()
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 390)
  await page.screenshot({ path: info.outputPath('trip-list-narrow.png'), fullPage: true })
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  await page.getByRole('link', { name: 'Live', exact: true }).click()
  await expect(page.getByTestId('connection')).toContainText('live')
  expect(errors).toEqual([])
})

test('failed correction refresh keeps labeled last-known summaries, not a false empty list', async ({ page }) => {
  let changed = false
  await page.route(/\/api\/v1\/vehicles\/venue-2021\/trips(?:[/?]|$)/, async route => {
    await route.fulfill({ status: changed ? 503 : 200, json: changed ? { code: 'PUBLICATION_PENDING' } : examples.list,
      headers: { 'X-Trip-Processing-State': 'REVIEW' } })
  })
  await page.goto('/#/trips'); await expect(page.getByRole('link', { name: /Trip starting/ })).toBeVisible()
  changed = true
  await page.getByRole('button', { name: 'Refresh newest trips' }).click()
  await expect(page.getByRole('alert')).toContainText('corrections are being published')
  await expect(page.getByText(/Showing retained last-known results/)).toBeVisible()
  await expect(page.getByRole('link', { name: /Trip starting/ })).toBeVisible()
  await expect(page.getByText(/Trip processing needs review/)).toBeVisible()
  await expect(page.getByText('No saved trips on this page')).toHaveCount(0)
})

test('bounded charts page raw readings, label aggregate means, and expose accessible unavailable history', async ({ page }, info) => {
  let expired = false
  await page.route(/\/api\/v1\/vehicles\/venue-2021\//, async route => {
    const url = new URL(route.request().url()), query = url.searchParams
    if (!url.pathname.endsWith('/telemetry/history')) {
      await route.fulfill({ json: examples.detail, headers: { 'X-Trip-Processing-State': 'CURRENT', 'X-Trip-Data-Generation': '1' } }); return
    }
    expect(query.get('tripId')).toBe(examples.detail.tripId)
    expect(query.get('to')).toBe('2026-10-20T12:00:08.000001Z')
    const resolution = query.get('resolution')!, from = query.get('from')!, to = query.get('to')!
    const end = { ...examples.historyRaw.points[2], reading: { ...examples.historyRaw.points[2].reading, sequenceNumber: 4,
      recordedAt: examples.detail.endedAt, rpm: 0, speedKph: 0, coolantTempC: 50, engineLoadPct: null } }
    const raw = { ...examples.historyRaw, from, to, points: query.has('cursor') ? [end] : examples.historyRaw.points, nextCursor: query.has('cursor') ? null : 'next-token' }
    const aggregate = { ...examples.historyAggregate, from, to, resolution, points: [{ ...examples.historyAggregate.points[0],
      bucketStart: from, bucketEnd: to, sampleCount: 4, lastObservedAt: examples.detail.endedAt, metrics: examples.detail.metrics }] }
    const unavailable = { ...examples.historyExpired, from, to }
    await route.fulfill({ json: resolution === 'raw' ? expired ? unavailable : raw : aggregate, headers: { 'X-Trip-Processing-State': 'CURRENT' } })
  })
  await page.goto(`/#/trips/${examples.detail.tripId}`)
  const history = page.getByRole('region', { name: 'Trip measurement charts' })
  await expect(history.locator('.history-plot')).toHaveCount(4)
  await expect(history.locator('svg.recharts-surface')).toHaveCount(4)
  await expect(history.getByText('3 displayed raw readings. More points are available; continue paging.')).toBeVisible()
  await history.screenshot({ path: info.outputPath('history-raw-desktop.png') })
  await history.getByText('Measurement values and sample coverage (accessible table)').click()
  await expect(history.getByRole('table')).toContainText('Unavailable (0/1)')
  await history.getByRole('button', { name: 'Next history page' }).click()
  await expect(history.getByText('1 displayed raw readings. No further page in this window.')).toBeVisible()
  await expect(history.getByRole('table')).toContainText('2026-10-20T12:00:08Z')
  await expect(history.getByRole('table')).toContainText('0 (1/1)')
  for (const resolution of ['1m', '5m']) {
    await history.getByLabel('History resolution').selectOption(resolution)
    await expect(history.locator('figcaption').first()).toContainText(`${resolution} bucket means`)
    await expect(history.getByText(`1 displayed ${resolution} aggregate buckets. No further page in this window.`)).toBeVisible()
  }
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 390)
  await history.getByText('Measurement values and sample coverage (accessible table)').click()
  const region = history.getByRole('region', { name: 'History values table, horizontally scrollable' })
  await region.focus(); await page.keyboard.press('ArrowRight')
  await expect.poll(() => region.evaluate(element => element.scrollLeft)).toBeGreaterThan(0)
  await history.screenshot({ path: info.outputPath('history-aggregate-narrow.png') })
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  expired = true
  await history.getByLabel('History resolution').selectOption('raw')
  await expect(history.getByText(/Raw history is expired/)).toBeVisible()
  await expect(history.locator('.history-plot')).toHaveCount(0)
  await expect(history.getByText(/not proof of no drive/)).toBeVisible()
  await history.getByLabel('History resolution').selectOption('1m')
  await expect(history.locator('.history-plot')).toHaveCount(4)
})

test('a summary/history generation mismatch requires summary refresh before charting', async ({ page }) => {
  let generation = '1'
  await page.route(/\/api\/v1\/vehicles\/venue-2021\//, async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/telemetry/history')) {
      await route.fulfill({ json: { ...examples.historyRaw, from: url.searchParams.get('from'), to: url.searchParams.get('to'), generation: '2' }, headers: { 'X-Trip-Processing-State': 'CURRENT' } })
    } else await route.fulfill({ json: { ...examples.detail, revision: Number(generation) }, headers: { 'X-Trip-Processing-State': 'CURRENT', 'X-Trip-Data-Generation': generation } })
  })
  await page.goto(`/#/trips/${examples.detail.tripId}`)
  await expect(page.getByRole('alert')).toContainText('History changed since this summary')
  await expect(page.locator('.history-plot')).toHaveCount(0)
  generation = '2'
  await page.getByRole('button', { name: 'Refresh trip' }).click()
  await expect(page.getByText('2 / trip-v1-draft')).toBeVisible()
  await expect(page.locator('.history-plot')).toHaveCount(4)
  await expect(page.getByRole('alert')).toHaveCount(0)
})
