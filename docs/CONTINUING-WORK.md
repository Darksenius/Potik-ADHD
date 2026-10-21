# Передача роботи: нативний Потік

Станом на 21.10.2026. Перед змінами прочитайте `AGENT_INSTRUCTIONS.md`, `docs/WORKFLOW.md` і N1–N5 у `docs/roadmap/NATIVE-MIGRATION.md`.

## Продукт і правила

Потік — офлайн Android-органайзер для дорослих з РДУГ: панель квестів показує доречні зараз справи, поруч — часові зони, план дня/тижня, нотатки й швидке введення зі шторки. Ціль — Kotlin/Compose та один транзакційний Repository для UI й сповіщень.

Зони дня і є напрямками справ, окремий другий перемикач напрямків не додавати. Проста задача може не мати часу, дати чи зони; це звичайна задача, не «Вхідні».

- «Зараз» і «Розгорнути» відбирають до п’яти доречних справ. Закріплені пріоритети йдуть першими лише якщо підходять поточній зоні, даті й часу.
- «Не зараз» переносить у наступну іменовану зону сьогодні, пропускаючи проміжки. Якщо у цій зоні задачу не виконано й знову не відкладено, вона повертається до початкової зони й показується при наступному її входженні; до того доступна в Плані/усіх задачах.
- Після останньої зони сьогодні «Не зараз» виконує правило «Не сьогодні».
- «Не сьогодні»: прив’язана задача — завтра у власну зону; без прив’язки — рівно на 23 години. Постійна прив’язка й повторення не переписуються.
- Пресет на дві дати створює незалежні виконання; повторне застосування до тієї самої дати не створює дублів і не скидає галочки.
- Активні дні не скидаються через пропуск. Перша змістовна дія дня відкриває наступну із 100 локальних думок; саме відкриття застосунку досвіду не дає.
- Блокнот автозберігається без кнопки. Швидка задача й нотатка зі шторки мають зберігатися без Activity/JS; повторна доставка не дублює запис або досвід.

Не повертайте Pomodoro, дитячу звичку, окремий водний шар чи «Вхідні». Віджет і великий редизайн поза цим переходом.

## Поточний стан і контрольна точка

- Гілка `refactor/native-compose`, інтегрований і відправлений у remote N5 — commit `186a367100db5e73bd223f1e978c7b2d921f4995` (`186a367`), версія `1.7.6-r26` / Android `1.7.6.r26` / code `1070626`. На початку роботи все одно звіряйте поточні `git status`, гілку й `git log`.
- N5 перемкнув основну Activity на нативну, прибрав старі Capacitor-компоненти й переніс 183 вебфайли байт-у-байт у `archive/web-reference/`. Код інтегрований, підписаний кандидат 186a367 перевірений на API28. Фінальний коміт із записом результатів та виправленнями ізоляції перевірок ще має пройти CI перед публікацією. Не робіть reset, clean, checkout поверх незбережених змін, force-push чи масове форматування. Для паралельної роботи — окремі гілки/worktree або неперетинні файли; один інтегратор переносить і перевіряє результат.
- Уже перенесено домен, Room/Repository, екрани задач, нотаток, плану, зон, пресетів, стану/статистики, налаштування й SAF. Деталі попередніх робіт — у roadmap; часткові успіхи не доводять готовність усієї програми.

## Шляхи в коді

- `android/app/src/main/java/com/flow/adhd/domain/`: правила й команди; ключові `FlowEngine.kt`, `FlowCommand.kt`, `FlowSnapshot.kt`, `QuestPolicy.kt`, `TaskSchedule.kt`, `ZoneCalendar.kt`, `PlannerProjection.kt`.
- `.../data/NativeCommandRepository.kt`, `CommandStore.kt`, `SnapshotPreparation.kt`, `NativeContent.kt`: єдиний writer, транзакційний ack, відновлення та assets.
- `.../ui/NativeFlowViewModel.kt`, `NativeFlowScreen.kt`, `NativeTasksScreen.kt`, `NativeTaskEditor.kt`, `NativeNotesScreen.kt`, `NativePlannerScreen.kt`, `NativeOrganizationScreens.kt`, `NativePresetScreens.kt`, `NativeProgressScreens.kt`, `NativeSettingsScreen.kt`: Compose UI.
- `android/app/src/main/java/com/flow/adhd/NativeFlowActivity.kt`, `NativeRuntime.kt`, `AppContainer.kt`, `FlowNotifService.java`, `FlowBridge.java`: запуск і Android/сповіщення.
- `src/content/` — авторські каталоги й джерела локальних assets. `archive/web-reference/` — лише довідка, не активна збірка.

## Перевірено й лишилося

- JVM 376/376; повний локальний API28 набір 55/55 (`handoff-native-build.log`, `handoff-api28-final.log`). Включено Undo, RemoteInput без Activity, реальне фонове нагадування нотатки, усі типи, пресети, DST, архіви та SAF з відмовою rename.
- Кандидат 186a367: [Release APK 39582064300](https://github.com/Darksenius/Potik-ADHD/actions/runs/39582064300), JVM/build і API28 успішні, підписаний APK та source ZIP з точним SOURCE_REVISION отримано. Підпис SHA256 `d95dc170ae4c88d62c3ff8e06c41b910cb3d2242285bc7c544dcfd790d351040` збігається з r25; signing config незмінний.
- На Potik_API28 із вимкненими Wi-Fi та мобільними даними: чиста установка підписаного APK → задача з нотаткою → автозбережений блокнот → SAF копія після згортання → видалення/встановлення → відновлення. У Room і новій копії дані присутні. Повторне відновлення не додало файлів, індекс і два blobs побайтово незмінні; індекс 1114 Б. Даних фізичного телефона не торкалися.
- Перезапуск процесу та повний reboot API28 зберегли 51 задачу, 27 нотаток і хеші ключових полів. Foreground service повернувся після boot без відкриття Activity. В APK немає вебресурсів, Capacitor і дозволу INTERNET; усі каталоги локальні.
- Паралельний push CI 39581975526 виявив два нестабільні тести: service start пробуджував runtime поверх fixture думки дня; натискання в Плані припадало на resize клавіатури. Виправлено лише тести; точні перевірки вмісту/створення збережено. Цільовий API28 прогін `ci-isolation-api28.log`: 2/2. Фінальний CI після цих змін ще звірити.
- Локальні логи/копії лише тестових даних: `C:\Users\kerla\Documents\Codex\2026-09-16\new-chat\artifacts\potik-r26\`; на іншому ПК використовуйте відповідні Actions artifacts. Не комітити ці дані.
- r26 ще не опубліковано; останній публічний реліз — v1.7.6.r25. Наступне: фінальний CI → підписаний APK точного фінального SHA → короткий запуск/метадані → реліз за WORKFLOW. Не повторювати всі успішні сценарії без нових змін чи конкретної причини. Фізичний телефон не перевірявся; це слід явно залишити у звіті.

## Чистий клон і APK без Node

Потрібні JDK 21 та Android SDK 36; для APK не потрібні Node/npm або секрети підпису. Збірки `debug` і `nativeDebug` не потребують емулятора; запуск instrumentation потребує окремого API28 емулятора.

```powershell
git clone --branch refactor/native-compose https://github.com/Darksenius/Potik-ADHD.git Potik
cd Potik
git status --short
git log -3 --oneline
$env:JAVA_HOME = '<JDK 21 path>'
$env:ANDROID_HOME = '<Android SDK 36 path>'
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
.\gradlew.bat :app:assembleNativeDebug
.\gradlew.bat -PflowTestBuildType=nativeDebug :app:connectedNativeDebugAndroidTest
```

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Окремий `nativeDebug` APK: `android/app/build/outputs/apk/nativeDebug/app-nativeDebug.apk`; він має application ID із суфіксом `.native` та окремі дані. Самої компіляції instrumentation недостатньо — потрібен запуск на API28.

## Стартовий prompt для іншого AI

> Продовж Potik у `refactor/native-compose` з актуального checkout. Звір git status і log та збережи всі наявні зміни. Спершу прочитай `AGENT_INSTRUCTIONS.md`, `docs/WORKFLOW.md`, `docs/roadmap/NATIVE-MIGRATION.md` та цей handoff. Не скидай і не перезаписуй незбережене. Перевір залишкові умови фінального приймання N5 та підписаного кандидата; Для кандидата 186a367 CI та підписаний smoke пройшли; звір фінальний SHA й залишкові кроки у розділі вище. Не повторюй успішні сценарії без нової причини. Виправляй лише підтверджені прогалини. Зберігай один Repository, Room v1, JSON formatVersion2, офлайн-роботу й погоджені правила; не додавай вилучених функцій. Не публікуй реліз до повної перевірки підписаного APK на API28 та фінального приймання; конфігурацію підпису не змінюй. Розділяй реалізоване, перевірене й незавершене. Після фактичних змін онови roadmap, `AGENT_INSTRUCTIONS.md` і цей `docs/CONTINUING-WORK.md` з SHA, командами, результатами та неперевіреним.

Оновлюйте handoff лише при зміні SHA/гілки, етапів або перевірок; не перетворюйте його на нову систему пам'яті чи повний аудит.
