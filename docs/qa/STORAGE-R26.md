# r26 — збереження, резервні копії та архіви

База main699354a; гілка fix/storage-backup-archive. Немає тегу/релізу,
змін підписування, нових залежностей або зміни Room v1. Фізичний телефон не підключався.

| Крок | Коміт | Основні файли / зміни |
|---|---|---|
| A | c591222 | FlowBackupStore, FlowPlugin, FlowBridge, MainActivity; TS контракт, backupFolder, autosave, Settings/Banner; версія/нотатки; SAF debug fixture й тести |
| B | 4b3e948 | RecoveryArchiveStore/Format, FlowBridge, persistence, webRecoveryArchive, backupFolder; JVM/Android/browser архівні тести |
| C | 8a64a93 | taskHistory, task-history.spec, measure-state-size, STORAGE-RETENTION |
| D | 3e834f6 | AndroidManifest, backup_rules, data_extraction_rules |
| E | поточний коміт | AGENT_INSTRUCTIONS, NATIVE-MIGRATION, release-notes, Fastlane, цей звіт |

Точні списки файлів: `git show --name-only <коміт>`; повний diff кожного кроку
зберігається окремо. Підготовлені B/C не включалися до коміту A.

## Виконані команди й результати

- `npm ci --no-audit --no-fund`, `npm run build`, `npm run sync`: успішно.
- Production Playwright: цільові data-safety/recovery-storage/save-recovery/
  migration/persistence-order/backup-folder: 60 успішних перевірок після виправлення
  повідомлення; повний набір — **206/206**. Після доданої перевірки ідентичного
  відновлення — backup-folder + web-recovery-archive **15/15**.
- task-history + web-recovery-archive: **16/16**.
- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`:
  успішно; **27 JVM** перевірок (25 QuestPolicy + 2 archive format).
- A instrumentation: FlowBackupStoreTest/FlowPersistenceTest/FlowWebViewTest —
  **15/15** на Android9/API28, WebView66, AVD Potik_API28.
- Після інтеграції B: SAF/Room/WebView не змінили результат; останню помилку
  сортування архівів виправлено, окремо RecoveryArchiveStoreTest — **2/2**.
  Разом покрито 17 актуальних Android-сценаріїв, повний набір після останнього
  точкового виправлення повторно не запускався.
- 1MiB payload після 10 повторень: один індекс <8KiB, один blob; старий v1
  читається й експортується, однаковий вміст не росте. Знайдені й виправлені
  Android JSONObject.NULL та однакові часові позначки архівів.
- Синтетичний рік (365 днів, 1460 подій, 730 фокусів): taskHistory
  648591→469741 байтів; focusLog109866 та planDayLog113698 не змінені.
  Разом872197→693347: **178850 байтів** економії без retention/compaction.

## Наскрізний Android9 сценарій

1. Через реальні Settings і ACTION_OPEN_DOCUMENT_TREE обрано локальний Downloads.
   flow_backup.json створено поза застосунком; запис/фон і збереження перевірено.
2. Дані емулятора до зміни підпису збережено в tar; фізичні дані не очищалися.
   Тестовий застосунок видалено. Зовнішній файл6304байти лишився з тим самим SHA256
   ba4729a2125020b0c4273529bf89bafb29f84697b324d9fb79395bf8d053c139.
3. Після встановлення debug APK папку повторно обрано через кнопку відновлення.
   Повернулись усі5задач, їхні нотатки й блокнот; порівняння з джерелом успішне.
4. Повторення через той самий системний picker: «Ці дані вже є в Потоку»;
   склад3файлів архіву/blobs до й після повністю збігається.

Логи та точні before/after дані лежать у локальному artifacts/potik-r26 цього чату.
Вони містять лише тестові дані; приватні дані не публікувалися.

## Межі та ручна перевірка власником

Не перевірено: фізичний телефон; API31+ cloud/device backup transport; поведінка
сторонніх/хмарних DocumentsProvider. Перевірено локальний Android9 provider;
провайдерна помилка не скасовує підтверджений Room commit і показується окремо.

1. Налаштування → обрати папку → зберегти задачу → згорнути → перевірити файл.
2. Лише після підтвердження справної копії: uninstall → install APK → відновити
   з тієї самої папки → перевірити задачі, нотатки, налаштування й прогрес.
3. Повторити відновлення однакового вмісту: новий дублікат архіву не з'являється.

Повний перехід Kotlin/Compose цим випуском не завершено: наступний крок N1
у погодженому NATIVE-MIGRATION.md, окрема гілка після r26.
