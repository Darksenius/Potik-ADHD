import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import packageJson from './package.json';
import { execFileSync } from 'node:child_process';

const revision = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();

// Capacitor WebView (androidScheme: "https", але без реального хоста) вимагає
// ВІДНОСНИХ шляхів до асетів — тому base:'./', а не '/'.
export default defineConfig({
  plugins: [react()],
  base: './',
  define: { __APP_VERSION__: JSON.stringify(packageJson.version.replace('-r', '.r')), __SOURCE_REVISION__: JSON.stringify(revision) },
  build: {
    // Capacitor читає веб-контент саме з www/ (див. capacitor.config.json → webDir).
    // Ця тека повністю генерується збіркою — більше не редагується вручну
    // і не зберігається в git (див. .gitignore).
    outDir: 'www',
    emptyOutDir: true,
    target: ['chrome69', 'es2018'],
    // Невеликий застосунок повністю офлайн — код-спліттінг тільки заважає
    // (зайві мережеві запити не потрібні, і так усе локально), тож зібран
    // в один бандл достатньо для старту. За потреби можна повернути chunking пізніше.
    assetsInlineLimit: 0, // шрифти .woff2 лишаємо окремими файлами, не base64
  },
  server: {
    // Зручно для розробки в звичайному браузері (без Android) на реальному пристрої в тій же мережі
    host: '127.0.0.1',
  },
});
