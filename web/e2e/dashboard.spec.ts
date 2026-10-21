import { test, expect } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import AxeBuilder from '@axe-core/playwright'

const python = process.env.AUTOPULSE_PYTHON ?? 'python'
const backend = process.env.AUTOPULSE_BACKEND_URL!
const root = resolve('..')
function send(args: string[]) {
  const result = execFileSync(python, ['-m', 'autopulse_agent.send', '--server-url', backend, ...args],
    { cwd: root, encoding: 'utf8', timeout: 20_000 })
  return JSON.parse(result) as { accepted: number; duplicate: number; rejected: number }
}

test('persisted simulator events update two tabs; stale, offline, replay, isolation and layouts remain honest', async ({ page, context }, info) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
  await page.goto('/')
  await expect(page.getByTestId('connection')).toContainText('live')
  await expect(page.getByText('No readings yet')).toBeVisible()
  const second = await context.newPage(); await second.goto('/')
  const other = await context.newPage(); await other.goto('/?vehicle=empty-vehicle')
  await expect(other.getByText('No readings yet')).toBeVisible()

  const capture = info.outputPath('snapshots.jsonl')
  // Backdate four simulated seconds before accelerating the three snapshots.
  const start = new Date(Date.now() - 4000).toISOString()
  expect(send(['--interval', '0.01', '--batch-size', '1', '--start-at', start, '--capture', capture]).accepted).toBe(3)
  await expect(page.getByTestId('metric-0')).toContainText('2,000')
  await expect(page.getByTestId('metric-1')).toContainText('30')
  await expect(second.getByTestId('metric-0')).toContainText('2,000')
  await expect(other.getByText('No readings yet')).toBeVisible()
  await expect(page.getByTestId('source')).toHaveText('SIMULATED')
  await expect(page.getByTestId('mil')).toHaveText('Reported off')
  await expect(page.getByText('MAF: Unavailable')).toBeVisible()
  await page.screenshot({ path: info.outputPath('desktop.png'), fullPage: true })
  const measured = await page.getByTestId('measured').textContent()
  expect(send(['--replay', capture]).duplicate).toBe(3)
  await expect(page.getByTestId('measured')).toHaveText(measured!)

  // Inject a distinct older identity through the actual Python HTTP transport.
  const first = JSON.parse(readFileSync(capture, 'utf8').split('\n')[1]).readings[0]
  first.sequenceNumber = 999
  first.recordedAt = new Date(Date.parse(start) - 60_000).toISOString()
  const code = 'import sys; from autopulse_agent.json_codec import loads; from autopulse_agent.transport.http import BatchSender,HttpBatchClient; assert BatchSender(HttpBatchClient(sys.argv[1])).deliver([loads(sys.argv[2])]).accepted == 1'
  execFileSync(python, ['-c', code, backend, JSON.stringify(first)], { cwd: root, timeout: 15_000 })
  await expect(page.getByTestId('measured')).toHaveText(measured!)

  await page.clock.install()
  await page.clock.fastForward(8000)
  await expect(page.getByTestId('freshness')).toHaveText('Stale measurement')
  await expect(page.getByTestId('connection')).toContainText('live')
  await page.screenshot({ path: info.outputPath('stale.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 390)
  await page.screenshot({ path: info.outputPath('narrow.png'), fullPage: true })
  const a11y = await new AxeBuilder({ page }).analyze()
  expect(a11y.violations).toEqual([])
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: 'AutoPulse' })).toBeFocused()

  // Cut real TCP streams in a test-only loopback proxy. Chromium's offline flag
  // does not reliably sever already-open SSE sockets, so it is not used here.
  const errorsBeforeOutage = [...errors]
  const control = process.env.AUTOPULSE_NETWORK_CONTROL!
  writeFileSync(control, 'offline')
  await expect.poll(() => existsSync(control + '.ack') ? readFileSync(control + '.ack', 'utf8') : '').toBe('offline')
  await expect(page.getByTestId('connection')).toContainText('reconnecting', { timeout: 20_000 })
  await expect(page.getByTestId('metric-0')).toContainText('2,000')
  writeFileSync(control, 'online')
  await expect.poll(() => readFileSync(control + '.ack', 'utf8')).toBe('online')
  await page.clock.runFor(4000)
  await expect(page.getByTestId('connection')).toContainText('live', { timeout: 20_000 })
  await expect(page.getByTestId('measured')).toHaveText(measured!)
  // A separate test-only device belongs to the other vehicle. Future input is
  // intentionally synthetic and must be visible as a clock warning, not fresh.
  const future = { ...first, deviceId: 'sim-empty-001', vehicleId: 'empty-vehicle', sequenceNumber: 0,
    recordedAt: new Date(Date.now() + 60_000).toISOString(), rpm: 0, speedKph: 0,
    coolantTempC: null, engineLoadPct: null, milOn: null }
  execFileSync(python, ['-c', code, backend, JSON.stringify(future)], { cwd: root, timeout: 15_000 })
  await expect(other.getByTestId('freshness')).toContainText('freshness unknown')
  await expect(other.getByTestId('engine-state')).toContainText('Engine-off reading')
  await expect(other.getByTestId('mil')).toHaveText('Unknown')
  await expect(other.getByTestId('metric-2')).toHaveText('Unavailable')
  await other.screenshot({ path: info.outputPath('future-engine-off.png'), fullPage: true })
  await expect(page.getByTestId('measured')).toHaveText(measured!)
  await second.close(); await other.close()
  expect(errorsBeforeOutage).toEqual([])
})
