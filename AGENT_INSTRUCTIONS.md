# Інструкції для роботи над Потоком

Це актуальні інструкції для React/TypeScript + Capacitor/Java застосунку. [README](README.md) описує збирання, [WORKFLOW](docs/WORKFLOW.md) — узгоджений процес. `README-REFACTOR.md` і `docs/REFACTOR-PLAN.md` — історія перенесення моноліту, а не поточне завдання чи перелік можливостей.

Поточне погоджене завдання від 16.10.2026: [r26 і нативний перехід](docs/roadmap/NATIVE-MIGRATION.md).
r26 виконується в `fix/storage-backup-archive` комітами A–E; теги, GitHub Releases та зміни підписування заборонені.
Після завершення r26 перехід Kotlin + Compose продовжується окремою гілкою `refactor/native-compose`.

## Межі змін

- Працювати лише в погодженій гілці й обсязі. Перед змінами перевірити `git status`; чужі зміни зберігати. Не відтворювати застосунок з нуля.
- Зберігати оборотність: невеликі логічні коміти, точна початкова ревізія, перевірений результат. Дозвіл на публікацію визначає поточне завдання й WORKFLOW; цей файл не є окремим дозволом.
- Дані фізичного телефона не очищати. Несумісні старі дані проходять наявне архівування. Не перейменовувати збережені поля або нативні команди без узгодженої міграції й тесту.
- Any change of the Room version requires a Migration and a test. fallbackToDestructiveMigration is forbidden.
- Контракт TypeScript ↔ Java змінювати разом з обома сторонами та перевірками. Android 9 лишається обов'язковим середовищем перевірки запуску й сповіщень.
- `www/`, синхронізовані Capacitor assets, Gradle outputs — згенеровані. Змінювати джерела, не готові файли. Ключі підпису, локальні налаштування й дані користувача не комітити.
- Поточний каталог десяти типів — `src/content/task-types.json`; `embed-oss` копіює його до `www/contracts/task-types.json` для Android. Не створювати другий ручний каталог.

## Збирання та перевірка

Потрібні Node.js 22+, JDK 21 і Android SDK 36. Використовувати lockfile через `npm ci`.

1. `npm run build` — перевірка запису «Що нового», TypeScript (включно з unused), Vite.
2. `npm run embed-oss` — текст ліцензії/джерел, точна ревізія та нативний каталог.
3. `npx cap sync android` — копіювання веб-збірки та каталогу.
4. Цільові Playwright-перевірки під час роботи; `TEST_PRODUCTION=1 PLAYWRIGHT_CHANNEL=chromium npm test` перед кандидатом. Chromium встановлюється через `npx playwright install chromium`.
5. З `android/`: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`. На Windows — `gradlew.bat`. Це JVM-тести та компіляція; вони не доводять виконання instrumentation на пристрої.
6. На призначеному емуляторі/пристрої окремо виконати instrumentation і smoke-сценарії. Вказати модель/API та фактичний результат, не підміняти їх браузером.

`npm run sync` об'єднує кроки 1–3. Не запускати сервери й повні набори повторно без змін або конкретної причини. Артефакти перевірок зберігати, а помилки автоматизації відділяти від помилок застосунку.

## Версія, джерела та реліз

Єдине джерело версії — `package.json`: `1.7.6-r10` перетворюється на Android/web `1.7.6.r10`, тег `v1.7.6.r10` і code `1070610`. Gradle читає пакет сам. Формула: `major*1000000 + minor*10000 + patch*100 + revision`; minor, patch і revision — 0..99. Оновлювати також lockfile, перший запис `src/content/release-notes.json` і Fastlane changelog за code.

Ревізія береться з валідного `SOURCE_REVISION` (повні 40 hex), локального Git або файла `SOURCE_REVISION` з релізного архіву. Звичайний source ZIP GitHub не містить Git: передайте його точний SHA через змінну. Без відомої ревізії дозволено локальну збірку з позначкою unknown, без посилання на вигаданий коміт.

`node scripts/package-source.mjs` у чистому checkout створює `dist/Potik-v<version>-source.zip`: усі tracked джерела, бінарні ресурси/шрифти, Gradle wrapper, lockfile та маркер ревізії. Вбудована текстова довідка — огляд джерел, а не заміна повного ZIP.

Tag workflow сам викликає reusable validation для того самого SHA: production browser tests + native JVM tests/компіляція. Лише успіх обох дозволяє підписання й чернетку релізу з APK та source ZIP. Секрети підпису доступні лише build job. Перед публікацією перевірити підписаний APK на Android, версію, фактичний asset і доступність завантаження; див. WORKFLOW.
