import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

const target = process.env.AUTOPULSE_BACKEND_URL ?? 'http://127.0.0.1:8080'
const parsed = new URL(target)
if (parsed.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(parsed.hostname)
    || parsed.username || parsed.password) throw new Error('Backend proxy must be unauthenticated loopback HTTP')

export default defineConfig({
  plugins: [react()],
  server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy: { '/api': {
    target, changeOrigin: false,
    configure(proxy) {
      proxy.on('proxyReq', (upstream, _request, downstream) => {
        // A closed tab must also close its upstream subscription, not leave an
        // orphaned server stream that continues accepting heartbeat writes.
        downstream.on('close', () => upstream.destroy())
      })
      // An upstream SSE socket can end after response headers were sent. Propagate
      // that termination instead of leaving the browser's response open forever.
      proxy.on('proxyRes', (response, _request, downstream) => {
        downstream.on('close', () => response.destroy())
        response.on('close', () => { if (!response.complete) downstream.destroy() })
        response.on('error', () => downstream.destroy())
      })
    },
  } } },
  test: { environment: 'jsdom', include: ['src/**/*.test.{ts,tsx}'], restoreMocks: true },
})
