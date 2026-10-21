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

- Гілка `refactor/native-compose`, контрольна точка перед N5 `ec0a2120a369bfd5c4ea7bb0c54c5ef3ed6e3888` (`ec0a212`); актуальний HEAD дивіться через `git log -1`, версія `1.7.6-r26` / Android `1.7.6.r26` / code `1070626`.
- Код завершального перемикання N5 підготовлено в цій гілці; перед роботою обов’язково перевіряйте незакомічене через `git status`. Не робіть reset, clean, checkout поверх них, force-push чи масове форматування. Для паралельної роботи — окремі гілки/worktree або неперетинні файли; один інтегратор переносить і перевіряє результат.
- N5 перемикає основну Activity на нативну, прибирає старі Capacitor-компоненти й переносить 183 вебфайли байт-у-байт у `archive/web-reference/`. Це не завершений або опублікований реліз.
- Уже перенесено домен, Room/Repository, екрани задач, нотаток, плану, зон, пресетів, стану/статистики, налаштування й SAF. Деталі попередніх робіт — у roadmap; часткові успіхи не доводять готовність усієї програми.

## Шляхи в коді

- `android/app/src/main/java/com/flow/adhd/domain/`: правила й команди; ключові `FlowEngine.kt`, `FlowCommand.kt`, `FlowSnapshot.kt`, `QuestPolicy.kt`, `TaskSchedule.kt`, `ZoneCalendar.kt`, `PlannerProjection.kt`.
- `.../data/NativeCommandRepository.kt`, `CommandStore.kt`, `SnapshotPreparation.kt`, `NativeContent.kt`: єдиний writer, транзакційний ack, відновлення та assets.
- `.../ui/NativeFlowViewModel.kt`, `NativeFlowScreen.kt`, `NativeTasksScreen.kt`, `NativeTaskEditor.kt`, `NativeNotesScreen.kt`, `NativePlannerScreen.kt`, `NativeOrganizationScreens.kt`, `NativePresetScreens.kt`, `NativeProgressScreens.kt`, `NativeSettingsScreen.kt`: Compose UI.
- `android/app/src/main/java/com/flow/adhd/NativeFlowActivity.kt`, `NativeRuntime.kt`, `AppContainer.kt`, `FlowNotifService.java`, `FlowBridge.java`: запуск і Android/сповіщення.
- `src/content/` — авторські каталоги й джерела локальних assets. `archive/web-reference/` — лише довідка, не активна збірка.

## Перевірено й лишилося

Артефакти: `C:\Users\kerla\Documents\Codex\2026-09-16\new-chat\artifacts\potik-r26\`.

- `handoff-native-build.log`: актуальний JVM 376/376, складання debug/nativeDebug та instrumentation.
- `handoff-api28-final.log`: повний актуальний послідовний API28 набір 55/55, включно з Undo та фоновим нагадуванням нотатки. Дані тестів нагадувань і архівів ізольовано; проміжні збої залишені в історичних логах.
- В APK немає вебресурсів, Capacitor і дозволу INTERNET; signing config звірено з ec0a212 без змін.
- Повторюйте повні перевірки перед новим кандидатом, якщо після цих результатів змінився код; без нових змін не запускайте їх по колу. Узгодьте старі тести з фактичним контрактом, не послаблюючи production-поведінку.
- Перевірте інтегровано: чистий запуск/перше збереження; спільний Repository UI та сповіщень; SAF backup/restore, перерваний rename, повторне архівування; офлайн-каталоги; process restart/boot; опівніч/DST; повторна доставка й Undo. Залишити Room v1 і JSON `formatVersion2`, зберігати невідомі поля; невдала архівація не має затирати джерело.
- r26 ще не опубліковано; останній підтверджений публічний реліз — v1.7.6.r25. Не тегуйте/публікуйте до завершення N2–N5, повної перевірки кандидата та підписаного APK на API28. Дозвіл на фінальну публікацію чинний після готовності; ключі й signing config не змінювати. Перевірку фізичного телефона не заявляти без фактичного тесту.

## Чистий клон і APK без Node

Потрібні JDK 21 та Android SDK 36; instrumentation потребує окремого API28 емулятора. Клонуйте після коміту інтегрованих змін: незбережений N5-стан не потрапляє у свіжий клон.

```powershell
git clone --branch refactor/native-compose https://github.com/Darksenius/Potik-ADHD.git Potik
cd Potik
git status --short
git log -3 --oneline
$env:JAVA_HOME = '<JDK 21 path>'
$env:ANDROID_HOME = '<Android SDK 36 path>'
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
.\gradlew.bat -PflowTestBuildType=nativeDebug :app:connectedNativeDebugAndroidTest
```

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`. `nativeDebug` має окремі application ID `.native` та дані. Самої компіляції instrumentation недостатньо — потрібен запуск на API28.

## Стартовий prompt для іншого AI

> Продовж Potik у `refactor/native-compose` з актуального checkout. Звір git status і log та збережи всі наявні зміни. Спершу прочитай `AGENT_INSTRUCTIONS.md`, `docs/WORKFLOW.md`, `docs/roadmap/NATIVE-MIGRATION.md` та цей handoff. Не скидай і не перезаписуй незбережене. Перевір конкретні незавершені N5-умови, потім доведи інтегрований застосунок до повного JVM та API28 прогону; виправляй лише підтверджені прогалини. Зберігай один Repository, Room v1, JSON formatVersion2, офлайн-роботу й погоджені правила; не додавай вилучених функцій. Не публікуй реліз до повної перевірки кандидата та підписаного APK на API28; конфігурацію підпису не змінюй. Розділяй реалізоване, перевірене й незавершене. Після фактичних змін онови roadmap, `AGENT_INSTRUCTIONS.md` і цей `docs/CONTINUING-WORK.md` з SHA, командами, результатами та неперевіреним.

Оновлюйте handoff лише при зміні SHA/гілки, етапів або перевірок; не перетворюйте його на нову систему пам'яті чи повний аудит.