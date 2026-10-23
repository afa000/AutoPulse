import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { existsSync, writeFileSync } from 'node:fs'

test('exact HTTP totals, safe means, raw and retained/owned aggregates load in the real browser', async ({ page }, info) => {
  test.setTimeout(120_000)
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('/#/trips')
  await expect(page.getByRole('link', { name: /Trip starting/ })).toHaveCount(1)
  await page.getByRole('link', { name: /Trip starting/ }).click()
  await expect(page.getByRole('row', { name: /Coolant temperature/ }).getByText('1e+308', { exact: true })).toHaveCount(3)
  await page.getByText('Exact analytical evidence (unrounded totals and distance ratio)').click()
  const read = async () => page.evaluate(async () => {
    const analyticsPath = '/src/analytics.ts', tripsPath = '/src/trips.ts', historyPath = '/src/history.ts'
    const { parseAnalyticsJson, exactText, displayRatio } = await import(analyticsPath)
    const { parseTrip, parseTripPage, estimatedDistance } = await import(tripsPath)
    const { parseHistory, historyWindow } = await import(historyPath)
    const evidence = []
    for (const vehicle of ['venue-2021', 'negative-vehicle', 'cancellation-vehicle']) {
      const listText = await (await fetch(`/api/v1/vehicles/${vehicle}/trips`)).text()
      const list = parseTripPage(parseAnalyticsJson(listText), vehicle)
      if (!list || list.items.length !== 1) throw Error('Valid large-total list rejected')
      const detailText = await (await fetch(`/api/v1/vehicles/${vehicle}/trips/${list.items[0].tripId}`)).text()
      const trip = parseTrip(parseAnalyticsJson(detailText), vehicle)
      if (!trip) throw Error('Valid large-total detail rejected')
      const metric = trip.metrics.coolantTempC, range = historyWindow(trip, 'raw', 0)
      const histories = []
      for (const resolution of ['raw', '1m', '5m']) {
        for (const filter of resolution === 'raw' ? [true] : [true, false]) {
          const parameters = new URLSearchParams({ from: range.from, to: range.to, resolution })
          if (filter) parameters.set('tripId', trip.tripId)
          const response = await fetch(`/api/v1/vehicles/${vehicle}/telemetry/history?${parameters}`)
          if (!response.ok) throw Error(await response.text())
          const parsed = parseHistory(parseAnalyticsJson(await response.text()), trip, range, resolution, 200)
          if (!parsed) throw Error('Valid large-total history rejected')
          histories.push({ resolution, filter, rawState: parsed.availability.rawState,
            values: parsed.rows.map((row: { values: unknown }) => row.values),
            totals: parsed.rows.map((row: { exactTotals: unknown }) => row.exactTotals ?? null) })
        }
      }
      evidence.push({ vehicle, count: metric.count, sum: exactText(metric.sum), mean: displayRatio(metric.sum, metric.count), histories,
        distance: estimatedDistance(trip), kilometres: displayRatio(trip.distance.kilometres.numerator, trip.distance.kilometres.denominator),
        oldBrowserOverflow: !Number.isFinite(JSON.parse(detailText).metrics.coolantTempC.sum) })
    }
    return evidence
  })
  const before = await read()
  for (const [index, row] of before.entries()) {
    expect(row.count).toBe(27)
    expect(row.oldBrowserOverflow).toBe(index !== 2)
    expect(row.mean).toBe(index === 0 ? 1e308 : index === 1 ? -1e308 : 1e308 / 27)
    expect(row.kilometres).toBe(Number('1.4444444444444444e306'))
    expect(row.distance).toContain('km'); expect(row.distance).not.toContain('range')
    expect(row.histories[0].values).toHaveLength(27)
    for (const h of row.histories.slice(1)) {
      expect(h.values).toHaveLength(1)
      expect((h.values[0] as { coolantTempC: number }).coolantTempC).toBe(row.mean)
    }
  }
  await page.getByLabel('History resolution').selectOption('1m')
  await expect(page.getByText('1 displayed 1m aggregate buckets.', { exact: false })).toBeVisible()
  await page.getByText('Measurement values and sample coverage (accessible table)').click()
  await expect(page.getByRole('table').last().getByText('1e+308 (27/27)', { exact: true })).toHaveCount(2) // speed and coolant
  await expect(page.getByRole('table').last().getByText('1e+308 (27/27)', { exact: true }).last()).toBeVisible()
  await page.getByText('Exact aggregate totals (unrounded decimal evidence)').click()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  await page.screenshot({ path: info.outputPath('large-total-desktop.png'), fullPage: true })
  writeFileSync(process.env.AUTOPULSE_NUMERIC_CONTROL!, 'prune')
  await expect.poll(() => existsSync(process.env.AUTOPULSE_NUMERIC_CONTROL! + '.ack')).toBe(true)
  const after = await read()
  for (const [index, row] of after.entries()) {
    expect(row.mean).toBe(before[index].mean); expect(row.sum).toBe(before[index].sum)
    expect(row.distance).toBe(before[index].distance)
    expect(row.histories[0].rawState).toBe('EXPIRED'); expect(row.histories[0].values).toEqual([])
    for (let h = 1; h < row.histories.length; h++) {
      expect(row.histories[h].values).toEqual(before[index].histories[h].values)
      expect(row.histories[h].totals).toEqual(before[index].histories[h].totals)
    }
  }
  await page.getByRole('button', { name: 'Refresh trip', exact: true }).click()
  await expect(page.getByText('Raw history is expired', { exact: false })).toBeVisible()
  await page.getByLabel('History resolution').selectOption('1m')
  await expect(page.getByText('1 displayed 1m aggregate buckets.', { exact: false })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  await page.screenshot({ path: info.outputPath('large-total-retained-narrow.png'), fullPage: true })
  expect(errors).toEqual([])
  console.log('NUMERIC_BOUNDARY_EVIDENCE positive+negative+cancellation=true raw+1m+5m+owned+retained=true meansFinite=true exactTokensRetained=true axe=0')
})
