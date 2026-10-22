# Потік ADHD

Нативний Android-органайзер із панеллю квестів: доречні зараз задачі, часові зони, План, нотатки та швидке введення зі шторки. Kotlin + Compose, ViewModel/StateFlow, чисте ядро правил і Room. Інтерфейс та сповіщення використовують один транзакційний Repository; WebView і JavaScript для роботи застосунку не потрібні.

Опублікована версія: [1.7.6.r31 — APK та джерела](https://github.com/Darksenius/Potik-ADHD/releases/tag/v1.7.6.r31). Нативний перехід завершено. [Карта екранів, сповіщень і продуктового задуму](docs/APP-MAP.md) пояснює, що відбувається в застосунку та навіщо. Для роботи з другом або іншим ШІ почніть з [AGENTS.md](AGENTS.md) і [передачі роботи](docs/CONTINUING-WORK.md). Історія — у [плані переходу](docs/roadmap/NATIVE-MIGRATION.md).

## Збирання

Потрібні JDK 21 та Android SDK 36. Задайте JAVA_HOME і ANDROID_HOME або android/local.properties. Node/npm не потрібні для складання APK. Версія читається з package.json як метадані.

```bash
cd android
./gradlew :app:assembleDebug
```

На Windows використовуйте gradlew.bat. APK: android/app/build/outputs/apk/debug/app-debug.apk. Збірка nativeDebug має окремий applicationId com.flow.adhd.native, щоб перевірки не змінювали основні дані.

## Структура та перевірка

[Код](android/app/src/main/java/com/flow/adhd/): domain обчислює правила й команди, data відповідає за транзакції, ui містить Compose-екрани. AppContainer з’єднує їх із сервісами Android.

[src/content/](src/content/) — єдині авторські каталоги десяти типів, 100 думок, нагород, ознайомчих задач і довідки. Gradle включає їх разом із ліцензією та текстовим оглядом коду в APK. Щоденне користування повністю офлайн; GitHub відкривається зовнішнім браузером.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew -PflowTestBuildType=nativeDebug :app:connectedNativeDebugAndroidTest
```

Друга команда потребує окремого тестового емулятора; Android 9/API 28 — обов’язковий. Компіляція instrumentation не означає його виконання. Дані фізичного телефона не очищаються. Room залишається v1, JSON — formatVersion2. Зміна Room потребує Migration і тесту; destructive migration заборонена.

Вебреалізацію збережено в [archive/web-reference/](archive/web-reference/) як довідку. Вона не входить до активного складання або APK.

## Версія та реліз

Версія в package.json і lockfile: 1.7.6-r26 → Android 1.7.6.r26, code1070626. Формула: major*1000000 + minor*10000 + patch*100 + revision. Для випуску потрібні особистий запис «Що нового» та Fastlane changelog.

Release workflow перевіряє той самий SHA через JVM/компіляцію та Android 9 instrumentation, потім збирає підписаний APK і створює чернетку з APK та джерелами. До публікації перевірте підписаний APK на API28. Конфігурація підпису та ключ не змінюються.

node scripts/package-source.mjs із чистого checkout створює dist/Potik-v<version>-source.zip з усіма tracked джерелами та маркером SOURCE_REVISION. Gradle читає ревізію зі змінної, Git або маркера архіву; без неї локальна збірка показує unknown.

Ліцензія: [AGPL-3.0-only](LICENSE). Залежності завантажує Gradle під час розробки; встановлений застосунок не підвантажує код або контент. [Інструкції](AGENT_INSTRUCTIONS.md), [WORKFLOW](docs/WORKFLOW.md).
