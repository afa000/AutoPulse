import { test, expect } from '@playwright/test'
import { spawn } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

test('human-paced six-phase commute ends engine-off then stale with a healthy stream', async ({ page }, info) => {
  test.setTimeout(110_000)
  await page.goto('/')
  await expect(page.getByTestId('connection')).toContainText('live')
  const capture = info.outputPath('live-commute.jsonl')
  const child = spawn(process.env.AUTOPULSE_PYTHON ?? 'python', ['-m', 'autopulse_agent.send',
    '--server-url', process.env.AUTOPULSE_BACKEND_URL!, '--scenario', 'normal-commute',
    '--phase-seconds', '4', '8', '16', '20', '8', '4', '--sample-interval', '2', '--interval', '2',
    '--batch-size', '1', '--capture', capture], { cwd: resolve('..'), windowsHide: true })
  let output = '', errors = ''
  child.stdout.on('data', chunk => { output += chunk })
  child.stderr.on('data', chunk => { errors += chunk })
  const done = new Promise<number | null>(resolveExit => {
    child.on('error', error => { errors += error.message; resolveExit(-1) })
    child.on('exit', code => resolveExit(code))
  })
  try {
    await expect(page.getByTestId('engine-state')).toContainText('Engine-off reading', { timeout: 7000 })
    await expect(page.getByTestId('metric-0')).not.toHaveText('0RPM', { timeout: 10_000 })
    await expect(page.getByTestId('metric-1')).not.toHaveText('0km/h', { timeout: 20_000 })
    await expect(page.getByTestId('source')).toHaveText('SIMULATED')
    await expect(page.getByTestId('freshness')).toHaveText('Recent measurement')
    await page.screenshot({ path: info.outputPath('live-city.png'), fullPage: true })
    await expect(page.getByTestId('metric-1')).toHaveText('85km/h', { timeout: 25_000 })
    await page.screenshot({ path: info.outputPath('live-highway.png'), fullPage: true })
    await expect(page.getByTestId('metric-0')).toHaveText('800RPM', { timeout: 25_000 })
    await expect(page.getByTestId('engine-state')).toContainText('Engine-off reading', { timeout: 15_000 })
    expect(await done, errors).toBe(0)
    expect(JSON.parse(output)).toEqual({ accepted: 31, duplicate: 0, rejected: 0 })
    const batches = readFileSync(capture, 'utf8').trim().split('\n').slice(1)
      .map(line => JSON.parse(line).readings as { recordedAt: string; sequenceNumber: number; rpm: number; bootId: string }[])
    const readings = batches.flat()
    expect(readings).toHaveLength(31)
    expect(new Set(readings.map(reading => reading.bootId)).size).toBe(1)
    expect(Date.parse(readings.at(-1)!.recordedAt) - Date.parse(readings[0].recordedAt)).toBe(60_000)
    expect(readings.map(reading => reading.sequenceNumber)).toEqual(Array.from({ length: 31 }, (_, index) => index))
    await expect(page.getByTestId('metric-0')).toHaveText('0RPM')
    await expect(page.getByTestId('metric-1')).toHaveText('0km/h')
    await expect(page.getByTestId('metric-2')).toHaveText('90°C')
    await expect(page.getByTestId('metric-3')).toHaveText('Unavailable')
    await expect(page.getByTestId('freshness')).toHaveText('Stale measurement', { timeout: 10_000 })
    await expect(page.getByTestId('connection')).toContainText('live')
    await expect(page.getByText(/Measurement time is ahead/)).toHaveCount(0)
    await page.screenshot({ path: info.outputPath('live-engine-off-stale.png'), fullPage: true })
    console.log('LIVE_COMMUTE_EVIDENCE accepted=31 phases=6 sampleInterval=2s sendInterval=2s final=engine-off-stale server=live')
  } finally {
    if (child.exitCode === null && !child.killed) child.kill()
    await done
  }
})
