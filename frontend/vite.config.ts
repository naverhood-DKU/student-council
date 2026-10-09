import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// npm run dev 로 띄우면 /api 요청을 로컬 Spring 서버(8080)로 넘긴다.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
})
