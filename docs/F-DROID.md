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

1. Зайди на https://gitlab.com/fdroid/rfp/-/issues (потрібен акаунт GitLab).
2. Натисни **New issue**. У полі вибору шаблону (Description template) обери
   **Submission** — GitLab сам підставить заготовку з полями.
3. Заголовок issue: `Потік (Potik) — offline ADHD organizer`.
4. Заповни поля шаблону (формулювання полів можуть трохи відрізнятись — шаблон
   часом оновлюють, але суть та сама). Готові відповіді:

   | Поле шаблону | Що вставити |
   |---|---|
   | Repo URL / Link to source code | `https://github.com/Darksenius/Potik-ADHD` |
   | Binaries / APK | `https://github.com/Darksenius/Potik-ADHD/releases` |
   | License | `AGPL-3.0-only` |
   | Categories | `Time` |
   | Summary | `Offline ADHD-friendly organizer: tasks, day zones, routines, hyperfocus` |
   | Description | можна не дублювати: `See fastlane/metadata/android/en-US/full_description.txt in the repo` |

5. У полі типу «Relevant info / additional notes» встав це (важливо для пакувальників):

   ```
   - Capacitor (webview) app. Build steps: `npm ci`, `node scripts/embed-oss.js`
     (embeds AGPL license text + full source code into the in-app help, required
     by the maintainer), `npx cap sync android`, then standard gradle
     `assembleRelease` in `android/app`.
   - A draft fdroiddata recipe is available in `docs/F-DROID.md` in the repo.
   - Fully offline: no trackers, no network calls, fonts bundled (OFL-1.1).
   - Fastlane metadata (uk + en-US) is in the repo, icon included.
   - Releases are tagged `v*`; versionCode = MAJOR*10000 + MINOR*100 + PATCH.
   - UI language: Ukrainian (English description provided).
   ```

6. У шаблоні є чекбокси (підтвердження, що застосунок FOSS, збирається з джерел,
   нема пропрієтарних залежностей, ти пошукав дублікати RFP) — проставити всі,
   у нас все це правда.
7. Надіслати і чекати. Чергу RFP розбирають волонтери — може тривати тижні або
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
