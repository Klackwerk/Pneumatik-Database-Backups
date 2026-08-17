import path from 'node:path'
import { readFileSync } from 'node:fs'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
const pkg = JSON.parse(readFileSync(path.resolve(__dirname, 'package.json'), 'utf8')) as {
  version: string
}

export default defineConfig({
  // AGPL section 13: the running app has to point its users at the source, and
  // the version is what makes that pointer useful.
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
  plugins: [react()],
  css: {
    preprocessorOptions: {
      scss: {
        // Bootstrap 5.3 still uses Sass constructs the compiler deprecates
        quietDeps: true,
        silenceDeprecations: ['import', 'color-functions', 'global-builtin'],
      },
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    proxy: {
      // anchored so the SPA route /api-keys is not proxied to the backend
      '^/api/': {
        target: 'http://localhost:8085',
        changeOrigin: true,
      },
    },
  },
})
