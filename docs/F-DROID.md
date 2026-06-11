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
