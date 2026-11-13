import { defineConfig } from 'vite';

// The page talks to paygate through this dev server, so the browser needs no CORS.
// PAYGATE_URL: where paygate listens (default the local one).
const paygate = process.env.PAYGATE_URL || 'http://127.0.0.1:8090';
const proxy = Object.fromEntries(['/payments', '/3ds', '/_demo'].map((path) => [path, { target: paygate, changeOrigin: true }]));

export default defineConfig({
  server: { host: '127.0.0.1', port: Number(process.env.PORT || 5173), strictPort: true, proxy },
  preview: { host: '127.0.0.1', port: Number(process.env.PORT || 5173), strictPort: true, proxy },
});
