import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App'
import examples from '../../contracts/examples/trip-api-examples.json'

afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/') })

it('loads a real JSON numeric token above binary64 range without losing the trip or its finite mean', async () => {
  const trip = structuredClone(examples.detail)
  trip.metrics.coolantTempC = { count: 4, sum: 4, min: 1, max: 1 }
  const body = JSON.stringify(trip).replace('"coolantTempC":{"count":4,"sum":4,"min":1,"max":1}',
    '"coolantTempC":{"count":4,"sum":4e308,"min":1e308,"max":1e308}')
  expect(JSON.parse(body).metrics.coolantTempC.sum).toBe(Infinity) // reproduces the old wire conversion
  window.history.replaceState(null, '', `/#/trips/${trip.tripId}`)
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { headers: { 'X-Trip-Processing-State': 'CURRENT' } })))
  render(<App />)
  await screen.findByRole('table', { name: 'Sensor statistics and non-null sample coverage' })
  expect(screen.queryByRole('alert')).toBeNull()
  expect(screen.getByRole('table').textContent).toContain('1e+308')
})
