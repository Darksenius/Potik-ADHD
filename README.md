# Потік

**Потік** — офлайн-органайзер для людей з РДУГ: задачі, часові зони, календар і конструктор тижня, нотатки, енергія та гіперфокус. Дані зберігаються на пристрої.

## Можливості

- Проста задача без дати потрапляє у Вхідні; дата пов'язує задачу зі спільним планом. Запланована задача має час і окреме керування нагадуванням.
- Десять типів задач: прості, чеклісти, лічильники, з нотаткою, будильники, заплановані, вікна часу, контекстні, шкідливі й прив'язані до зони. [Приклади й межі](docs/roadmap/TASK-TYPES.md).
- Спільні текстові деталі та додатковий лічильник із власним кроком і одиницею. Виконання пунктів або досягнення цілі не завершує всю задачу автоматично.
- Зони дня, шаблони й графіки тижня; майбутні входження повторюваних задач у календарі.
- Швидкі нотатки, папки, блокнот, «Колись», JSON export/import та резервні копії.
- Android-сповіщення з явним створенням задачі/нотатки й діями над задачами; XP, рівні та відстеження фокусу.

APK та опис випусків: [GitHub Releases](https://github.com/Darksenius/Potik-ADHD/releases).
Наявні межі й фактичні результати перевірок фіксуються в [дорожній карті](docs/roadmap/) і [QA-звітах](docs/qa/).

## Збирання

Потрібні Node.js 22+, JDK 21, Android SDK 36; для локального Gradle задайте `JAVA_HOME` та `ANDROID_HOME` або `android/local.properties`.

```bash
npm ci
npm run build
npm run embed-oss
npx cap sync android
cd android
./gradlew assembleDebug
```

На Windows використовуйте `gradlew.bat`. APK: `android/app/build/outputs/apk/debug/app-debug.apk`. `npm run sync` об'єднує web build, embedding і Capacitor sync. Копіювання APK в іншу папку запускається лише явно через `-PflowApkOutput=<directory>`.

React/TypeScript і Zustand — у [src/](src/); Android Java — у [android/app/src/main/java/com/flow/adhd/](android/app/src/main/java/com/flow/adhd/). `www/` генерується. Каталог `src/content/task-types.json` є спільним джерелом типів; під час embedding копіюється до нативних assets як `contracts/task-types.json`.

## Перевірки й версії

```bash
npx playwright install chromium
TEST_PRODUCTION=1 PLAYWRIGHT_CHANNEL=chromium npm test
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

Змінні перед командою наведено для shell на Linux/macOS; у PowerShell задайте `$env:TEST_PRODUCTION='1'` та `$env:PLAYWRIGHT_CHANNEL='chromium'`. Playwright-звіт: `playwright-report/index.html`. Android-команда виконує JVM-тести та компілює застосунок/instrumentation; перевірки на Android 9 запускаються окремо.

Версію змінюють у `package.json` і lockfile. Gradle читає її автоматично: `1.7.6-r10` → `1.7.6.r10`, code `1070610`. Формула: `major*1000000 + minor*10000 + patch*100 + revision` (кожне з трьох останніх чисел 0..99). Додайте запис «Що нового» й Fastlane changelog перед збіркою.

Release workflow викликає production-browser і native validation для **того самого SHA**, після успіху створює чернетку з підписаним APK та повним source ZIP. Публікації передує перевірка підписаного APK. [Актуальні інструкції](AGENT_INSTRUCTIONS.md), [узгоджений процес](docs/WORKFLOW.md). Документи `README-REFACTOR.md`, `docs/REFACTOR-PLAN.md` та [старі CDP-експерименти](archive/legacy-cdp/) є історичними.

## Ліцензія та джерела

Проєкт: [AGPL-3.0-only](LICENSE). Ліцензійні повідомлення шрифтів: [src/assets/fonts/](src/assets/fonts/).

`scripts/embed-oss.js` додає до застосунку ліцензію та **текстовий огляд** джерел, включно з Java, Gradle, lockfile, XML-ресурсами й повідомленнями шрифтів. Бінарні шрифти, зображення та Gradle wrapper не перетворюються на текст. Повні tracked джерела й ресурси для повторного збирання містяться у `Potik-v<version>-source.zip` поруч із APK; архів створює `node scripts/package-source.mjs` з чистого checkout.

Релізний ZIP містить `SOURCE_REVISION`, тому збирається без `.git` зі збереженням точного SHA. Для звичайного ZIP з GitHub передайте `SOURCE_REVISION=<40-значний SHA>`; без цього поза Git показується unknown, без фальшивого посилання на коміт. Залежності встановлюються через npm/Gradle за конфігурацією проєкту; побітову тотожність APK окремо не заявлено.
