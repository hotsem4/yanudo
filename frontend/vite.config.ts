import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

// 개발 때만 /api 를 Spring Boot 로 넘긴다(기본 8080). 운영은 8080 한 포트에서 dist 를 함께 서빙한다.
// 다른 포트의 서버를 쓰려면 HABIT66_API 를 지정한다(예: .env.preview).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const target = env.HABIT66_API || "http://127.0.0.1:8080";
  return {
    plugins: [react()],
    server: {
      host: true,
      port: Number(env.PORT) || 5173,
      proxy: {
        "/api": { target, changeOrigin: true },
      },
    },
    build: {
      outDir: "dist",
      emptyOutDir: true,
      sourcemap: false,
    },
  };
});
