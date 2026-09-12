import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Capacitor WebView (androidScheme: "https", але без реального хоста) вимагає
// ВІДНОСНИХ шляхів до асетів — тому base:'./', а не '/'.
export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    // Capacitor читає веб-контент саме з www/ (див. capacitor.config.json → webDir).
    // Ця тека повністю генерується збіркою — більше не редагується вручну
    // і не зберігається в git (див. .gitignore).
    outDir: 'www',
    emptyOutDir: true,
    // Невеликий застосунок повністю офлайн — код-спліттінг тільки заважає
    // (зайві мережеві запити не потрібні, і так усе локально), тож зібран
    // в один бандл достатньо для старту. За потреби можна повернути chunking пізніше.
    assetsInlineLimit: 0, // шрифти .woff2 лишаємо окремими файлами, не base64
  },
  server: {
    // Зручно для розробки в звичайному браузері (без Android) на реальному пристрої в тій же мережі
    host: true,
  },
});
