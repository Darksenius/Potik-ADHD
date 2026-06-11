# Випуск на F-Droid

## Що вже готово в репозиторії

- ✅ **Ліцензія**: AGPL-3.0-only, файл `LICENSE` з першого коміту
- ✅ **Без невільних залежностей**: тільки androidx, Room, Capacitor (MIT) — усе з Maven Central / npm
- ✅ **Без трекерів, реклами, мережевих сервісів**: шрифти Space Mono та Syne вшиті локально
  (`www/fonts/`, ліцензія OFL-1.1), запитів до fonts.googleapis.com більше немає
- ✅ **Без бінарних блобів** у git (keystore та згенеровані Capacitor-файли в .gitignore;
  `gradle-wrapper.jar` — дозволений виняток)
- ✅ **Збірка без ключа підпису**: `assembleRelease` без env-змінних дає непідписаний APK —
  саме його F-Droid підписує власним ключем
- ✅ **versionCode монотонний**: MAJOR·10000 + MINOR·100 + PATCH (1.6.1 → 10601)
- ✅ **Теги релізів**: `v*` — F-Droid автооновлюється по тегах
- ✅ **Fastlane-метадані**: `fastlane/metadata/android/{uk,en-US}/` — назва, описи,
  changelog-и (за versionCode), іконка 512×512

## Кроки подачі (робить мейнтейнер вручну)

1. Зареєструватись на https://gitlab.com (якщо нема акаунта).
2. Найпростіший шлях — **RFP (Request For Packaging)**: створити issue в
   https://gitlab.com/fdroid/rfp/-/issues з посиланням на репозиторій. Пакувальники
   F-Droid зроблять решту самі.
3. Швидший шлях — **самостійний MR у fdroiddata**:
   - форкнути https://gitlab.com/fdroid/fdroiddata
   - додати файл `metadata/com.flow.adhd.yml` (чернетка нижче)
   - локально перевірити: `fdroid readmeta`, `fdroid lint com.flow.adhd`,
     `fdroid build -v -l com.flow.adhd` (потрібен fdroidserver, докер-образ
     `registry.gitlab.com/fdroid/fdroidserver:buildserver`)
   - відкрити Merge Request
4. Після прийняття апка з'явиться на f-droid.org протягом кількох днів (цикл збірки).
   Нові версії підхоплюються автоматично по тегах `v*` (AutoUpdateMode: Version).

## Подача через RFP — покроково

1. Зайди на https://gitlab.com/fdroid/rfp/-/issues (потрібен акаунт GitLab) →
   **New issue**. Заголовок: `Потік (Potik) — offline ADHD organizer`.
2. Чекбокси шаблону — позначити перші три (Donated — лише якщо реально донатив):
   - [x] The app complies with the inclusion criteria — FOSS, без трекерів і
     пропрієтарних залежностей
   - [x] The app is not already listed in the repo or issue tracker — перед
     подачею пошукай "Potik" по issues для певності
   - [x] The original app author has been notified (and does not oppose the
     inclusion) — ти і є автор
   - [ ] Donated to support the maintenance of this app in F-Droid
3. Поля — вставити як є:

   ```
   Link to the source code: https://github.com/Darksenius/Potik-ADHD

   Link to app in another app store: https://github.com/Darksenius/Potik-ADHD/releases (GitHub Releases only, not on Google Play)

   License used: AGPL-3.0-only

   Category: Time

   Summary: Offline ADHD-friendly organizer: tasks, day zones, routines, hyperfocus

   Description:
   Potik ("Flow" in Ukrainian) is an external executive-function support system
   for people with ADHD. Instead of asking "what should I do?", it answers
   "what is best right now?". The day is split into colored time zones, tasks
   are ranked by the current zone; hyperfocus tracking, impulse capture into a
   notebook, routines, XP/levels, persistent notification with task actions.
   Works fully offline, all data stays on the device, no ads, no trackers,
   no accounts. UI is currently Ukrainian only.

   Additional notes for packagers:
   - Capacitor (webview) app. Build: `npm ci`, `node scripts/embed-oss.js`
     (embeds the AGPL license text and full source code into the in-app help),
     `npx cap sync android`, then standard gradle `assembleRelease` in `android/app`.
   - Draft fdroiddata recipe: see `docs/F-DROID.md` in the repo.
   - Fastlane metadata (uk + en-US) with icon and changelogs is in the repo.
   - Releases are tagged `v*`, versionCode = MAJOR*10000 + MINOR*100 + PATCH
     (suggested: AutoUpdateMode: Version, UpdateCheckMode: Tags ^v[0-9.]+$).
   - Fully offline: no network calls, fonts bundled (OFL-1.1).
   ```

4. Надіслати і чекати. Чергу RFP розбирають волонтери — може тривати тижні або
   місяці. Якщо хочеться швидше — паралельно можна зробити MR у fdroiddata
   самому (див. нижче), тоді в RFP-issue додай посилання на свій MR.

## Автооновлення у F-Droid з нових релізів GitHub

Нічого додатково робити не треба — у рецепті вже є:

```yaml
AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
```

Працює так: бот F-Droid (checkupdates) періодично сканує git-теги репозиторію.
Побачив новий тег `vX.Y.Z` → прочитав versionName/versionCode з
`android/app/build.gradle` → сам додав новий блок збірки у fdroiddata → сервер
зібрав і виклав APK. Тобто твій звичний флоу «підняв версію в build.gradle →
запушив тег v*» автоматично оновлює і GitHub Releases (через наш CI), і F-Droid.
Затримка у F-Droid — від 1 до ~7 днів (їхній цикл збірки/підпису).

Єдине правило: НЕ перевикористовувати і не пересувати теги, кожен реліз —
новий тег і більший versionCode.

## Скриншоти

Кладуться прямо в репозиторій:

```
fastlane/metadata/android/uk/images/phoneScreenshots/1.png
fastlane/metadata/android/uk/images/phoneScreenshots/2.png
fastlane/metadata/android/en-US/images/phoneScreenshots/1.png
...
```

- Порядок на сторінці = алфавітний порядок імен файлів (тому 1.png, 2.png, …).
- Формат PNG або JPG, звичайні скріни з телефона підходять як є.
- ⚠ У .gitignore є патерн `s*.png` — НЕ називати файли screenshot1.png тощо,
  цифрові імена безпечні.
- З'являться на f-droid.org після наступної збірки апки (бот тягне їх з гілки
  за замовчуванням).

## Чернетка metadata/com.flow.adhd.yml

```yaml
Categories:
  - Time
License: AGPL-3.0-only
AuthorName: Darksenius
SourceCode: https://github.com/Darksenius/Potik-ADHD
IssueTracker: https://github.com/Darksenius/Potik-ADHD/issues
Changelog: https://github.com/Darksenius/Potik-ADHD/releases

AutoName: Потік
Summary: Offline ADHD-friendly organizer

RepoType: git
Repo: https://github.com/Darksenius/Potik-ADHD.git

Builds:
  - versionName: 1.6.1
    versionCode: 10601
    commit: v1.6.1
    subdir: android/app
    sudo:
      - apt-get update
      - apt-get install -y --no-install-recommends npm nodejs
    init:
      - cd ../.. && npm ci --no-audit --no-fund
    prebuild:
      - cd ../.. && node scripts/embed-oss.js && npx cap sync android
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
CurrentVersion: 1.6.1
CurrentVersionCode: 10601
```

Примітки:
- Точний синтаксис кроків (`sudo`/`init`/`prebuild`) пакувальники можуть підправити під
  свій buildserver — це нормальна частина рев'ю MR.
- Якщо версія npm/nodejs у Debian виявиться застарою для Capacitor 8, у рецепті
  замінюють установку на nodejs із buildserver-провізії (підкажуть у рев'ю).

## Що ще варто зробити (не блокує подачу)

- **Скриншоти**: `fastlane/metadata/android/uk/images/phoneScreenshots/1.png, 2.png…` —
  сильно піднімають конверсію на сторінці апки. УВАГА: у .gitignore є патерн `s*.png`,
  тому не називати файли на «s».
- **Прибрати дозвіл INTERNET** з AndroidManifest.xml: після вшивання шрифтів застосунок
  не робить жодного мережевого запиту, а бейдж «без Інтернету» — найкраща реклама на
  F-Droid. Потребує перевірки на пристрої (Capacitor віддає assets через перехоплення
  запитів WebView, сокети не потрібні — але краще переконатись).
- Переклад інтерфейсу англійською — розширить аудиторію (зараз UI лише українською).
