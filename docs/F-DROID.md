# Підготовка пакета F-Droid

Це **чернетка для пакувальника**, а не підтвердження прийняття застосунку до F-Droid чи успішної збірки на його сервері. Актуальні локальні команди — у [README](../README.md).

## Джерела й версія

- Ліцензія проєкту: `AGPL-3.0-only`; повідомлення локальних шрифтів — `src/assets/fonts/OFL-*.txt`.
- Вхідні файли: React/TypeScript `src/`, Android Java/ресурси, Gradle wrapper, `package-lock.json`. `www/` і Capacitor assets генеруються.
- Єдине джерело версії: `package.json`. `1.7.6-r10` → `versionName 1.7.6.r10`, тег `v1.7.6.r10`, `versionCode 1070610`.
- Формула code: `major*1000000 + minor*10000 + patch*100 + revision`; minor/patch/revision у межах 0..99. Старі changelog-файли з короткими кодами описують історичні випуски.
- Порядок: `npm ci` → `npm run build` → `npm run embed-oss` → `npx cap sync android` → Gradle `assembleRelease`. Потрібні Node.js 22+, JDK 21, SDK 36.
- Без signing env або локального `keystore.properties` Gradle створює непідписаний release для підписання пакувальником.
- Повний source ZIP релізу включає бінарні ресурси та `SOURCE_REVISION`; вбудована текстова довідка не є повним архівом.

## Чернетка metadata/com.flow.adhd.yml

Перед застосуванням пакувальник має перевірити provisioning Node/JDK/SDK, доступність залежностей, ліцензії та вимоги F-Droid. Тег нижче — ціль r10; наявність опублікованого тега/релізу перевіряється окремо.

```yaml
Categories:
  - Time
License: AGPL-3.0-only
AuthorName: Darksenius
SourceCode: https://github.com/Darksenius/Potik-ADHD
IssueTracker: https://github.com/Darksenius/Potik-ADHD/issues
Changelog: https://github.com/Darksenius/Potik-ADHD/releases
AutoName: Потік
RepoType: git
Repo: https://github.com/Darksenius/Potik-ADHD.git

Builds:
  - versionName: 1.7.6.r10
    versionCode: 1070610
    commit: v1.7.6.r10
    subdir: android/app
    init:
      - cd ../.. && npm ci --no-audit --no-fund
    prebuild:
      - cd ../.. && npm run build && npm run embed-oss && npx cap sync android
    gradle:
      - yes

AutoUpdateMode: None
UpdateCheckMode: None
CurrentVersion: 1.7.6.r10
CurrentVersionCode: 1070610
```

Автооновлення навмисно не обіцяне: Gradle обчислює версію з JSON, і статичний сканер пакувальника може її не прочитати. Після перевірки рецепта слід окремо налаштувати й випробувати правила оновлення; актуальний формат тегів — `^v[0-9]+\\.[0-9]+\\.[0-9]+\\.r[0-9]+$`. Не пересувати вже випущені теги.

## Подання та метадані

Мейнтейнер може підготувати RFP або merge request у fdroiddata. Перед поданням перевірити наявність пакета, виконати `fdroid readmeta`, `fdroid lint com.flow.adhd` та повну збірку на відповідному buildserver. Ці перевірки в межах локальної підготовки r10 не виконувалися.

Назви, описи й changelog за versionCode: `fastlane/metadata/android/{uk,en-US}/`. Поточний файл — `changelogs/1070610.txt`. Скріншоти класти як `images/phoneScreenshots/1.png`, `2.png` тощо; перевіряти, що вони відповідають поточному UI. Не вказувати вилучені типи Pomodoro, Звичка чи Дитяча як доступні.
