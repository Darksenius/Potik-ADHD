# Потік — Крок 0 рефакторингу (застосувати поверх існуючого репо)

Цей архів містить лише НОВІ й ЗМІНЕНІ файли — не весь репозиторій
(`android/`, `fastlane/`, `docs/F-DROID.md`, `LICENSE`, `.gitignore`-записи
поза www/ лишаються як є і сюди не включені, щоб не роздувати архів).

## Як застосувати

```bash
# з кореня Potik-ADHD:
unzip potik-refactor.zip -d .
# або скопіюй вміст вручну, поверх наявних файлів

npm install     # нові залежності: react, react-dom, zustand, vite, typescript...
npm run dev     # Vite dev-сервер — відкриє Крок-0 екран-перевірку в браузері
```

Щоб зібрати для Android так само, як і раніше, послідовність команд трохи
змінилась (`npm run build` тепер обов'язковий перед embed-oss):

```bash
npm run build              # vite build → www/ (новий крок!)
node scripts/embed-oss.js  # як і раніше, але тепер читає src/ замість www/index.html
npx cap sync android
cd android && ./gradlew assembleDebug
```

Або одразу все разом: `npm run sync`.

## Що всередині

| Файл / тека                              | Що з ним сталось |
|--------------------------------------------|---|
| `package.json`                             | оновлено: +vite/react/typescript/zustand, новий порядок скриптів |
| `vite.config.ts`, `tsconfig*.json`         | нові — конфігурація збірки |
| `index.html` (корінь)                      | нова вхідна точка Vite (заміняє www/index.html ЯК ДЖЕРЕЛО) |
| `.gitignore`                                | оновлено: додано `www/` (тепер build-артефакт) |
| `scripts/embed-oss.js`                     | оновлено: обходить `src/` замість одного файлу, працює ПІСЛЯ build |
| `.github/workflows/release.yml`            | оновлено: додано крок `npm run build` перед embed-oss |
| `docs/REFACTOR-PLAN.md`                    | новий — повна мапа секцій оригіналу → нові модулі + покроковий план |
| `src/types/index.ts`                       | нові — точні типи (Task 13 типів, Zone, PlannerState, AppState...) |
| `src/styles/tokens.css`                    | перенесено 1:1 — CSS-змінні + @font-face |
| `src/assets/fonts/*`                       | перенесено 1:1 з www/fonts/ |
| `src/services/persistence.ts`              | перенесено 1:1 — collectState/applyState/loadState/saveState |
| `src/services/dailyReset.ts`               | TODO-заглушка (чесна, з точними рядками оригіналу) |
| `src/bridge/nativeBridge.ts`               | перенесено — контракт FlowBridge/FlowNotif + handleNativeEvent |
| `src/state/store.ts` + `slices/*`          | нові — gamification і routine повністю перенесені (еталонний приклад); tasks/notes частково (те, що потрібне nativeBridge); zones/planner/focus/ui — сіди + TODO |
| `src/main.tsx`, `src/App.tsx`              | нові — bootstrap і Крок-0 екран-перевірка (НЕ фінальний UI) |
| `src/components/*/.gitkeep`                | порожні теки під Крок 3 |

## Що НЕ чіпали

- `android/**` — жодних змін, контракт FlowBridge/FlowNotif лишається тим
  самим Java-кодом.
- `fastlane/`, `docs/F-DROID.md`, `LICENSE` — без змін.
- Візуальний дизайн — поки що НЕ перенесений у компоненти (це Крок 3–4);
  `App.tsx` зараз навмисно є лише екраном-перевіркою каркасу, не копією
  оригінального UI.

## Наступний крок

Функціональну міграцію завершено — усі 7 вкладок, TaskEditor, Довідка,
Android-сповіщення, Web-сповіщення, Export/Import/AI-промпт, банер
відновлення з бекапу. Кожен шматок пройшов автоматизовану перевірку
(`tsc`, `vite build`, `embed-oss.js`) у копії реального репозиторію.

Це НЕ замінює твою власну перевірку в браузері/на телефоні — я не можу
відкрити застосунок і поклацати по ньому в цьому середовищі. Дивись
`docs/REFACTOR-PLAN.md`, розділ 4 («Чекліст верифікації паритету») —
це і є справжній наступний крок.
