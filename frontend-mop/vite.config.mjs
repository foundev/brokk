import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'

export default defineConfig(({ command }) => ({
  plugins: [svelte()],
  build: {
    outDir: '../src/main/resources/mop-web',
    emptyOutDir: true
  },
  // Only for `vite dev`
  server: {
    port: 5173,
    // Open /dev.html instead of /
    open: command === 'serve' ? '/dev.html' : undefined
  }
}))
