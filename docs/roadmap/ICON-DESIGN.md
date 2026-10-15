# Графіка Потоку · 1.7.6.r6

Вбудований imagegen, 14.10.2026. Намальовано новий спрайтлист і відредаговано чинний логотип. Старий знак з двома діагональними потоками збережений як основа; креслярську сітку прибрано, кути пом'якшено. Це зміна ресурсів і окремих компонентів, без перебудови застосунку.

## Файли та інтеграція

- `public/brand/potik-icons.png`: 1536 × 1024, RGBA, 24 іконки у сітці 6 × 4. `Icon.tsx` бере потрібну клітинку через CSS background-position; жодних мережевих завантажень чи шрифтових емодзі для навігації.
- `public/brand/potik-mark.png`: прозорий знак для верхньої панелі.
- `android/app/src/main/res/drawable-nodpi/potik_mark.png`: той самий знак у Android. XML додає тло й відступи під адаптивну маску; початковий файл не обрізається.
- Кожна кнопка лишає текстову/доступну назву. Іконки декоративні, `aria-hidden`. Збережений користувацький текст і довільні значки папок не переписуються.

| Рядок | Клітинки зліва направо |
|---|---|
| 1 | quest, calendar, inbox, more, settings, help |
| 2 | note, focus, zones, idea, chart, folder |
| 3 | check, clock, repeat, counter, archive, trash |
| 4 | edit, plus, close, expand, moon, sun |

## Остаточний промпт спрайтлиста

```
Use case: logo-brand. Flat production icon atlas. EXACT 24 monochrome dark-ink icons on a fully transparent background, in an exact 6 columns by 4 rows uniform grid on a 1536x1024 canvas. A vector icon reference sheet, like clean screen-printed technical line art. NO lighting, NO glow, NO gradients, NO shadow, NO texture, NO color cloud, NO background, NO text. Only crisp fully opaque strokes and small fills, transparent empty pixels elsewhere. Use one solid color #b6d9f2. Rounded geometric strokes of uniform weight; restrained medium detail, easily understood at small size. Every icon occupies 70 percent of its own cell, centered exactly and aligned. Each row has SIX symbols left to right. Row1: quest checklist with one check and two lines; calendar; inbox tray with down arrow; three dots; gear; circled question mark. Row2: folded note sheet; focus bullseye; clock divided into three sections representing time zones; lightbulb; bar chart; folder. Row3: check in rounded square; clock hands; two-arrow repeat loop; tally counter with plus; archive box; trash can. Row4: pencil; plus; X; expand four corner brackets; crescent moon; sun. Preserve exact row order. Minimalist custom UI family with some distinctive geometry, no emoji, no decorative effects. Transparent PNG with actual alpha.
```

## Промпт покращення логотипа

Вхідний файл: `fastlane/metadata/android/en-US/images/icon.png`, оглянутий перед редагуванням.

```
Use case: logo-brand. Edit target is the current Potik app logo in the referenced image. Preserve its identity: two intersecting diagonal blue directional paths / arrows suggesting flow. Improve into a polished Android launcher mark: remove the white drafting grid, white panel, black frame, all background and all fine construction lines. Make the two crossing paths more legible and intentional, softly rounded geometric corners, controlled medium blue #459eea with one very small mint accent at a route endpoint. Flat opaque shapes, no lighting, glow, textures, shadows, text, letters or wordmark. The mark alone on a genuinely transparent square canvas 1024x1024, perfectly centered, within central 64 percent so Android adaptive masks will not clip it. Prefer two distinct interlocking flowing chevrons with clear negative space, referencing the original crossed-arrow silhouette, not a generic checkmark and not a play button. Clean minimalist but recognizable app icon, original geometry, holds up at 48px.
```

Перші варіанти з кольоровим сяйвом відхилено. У застосунок включено стриманіший монохромний спрайтлист. Малі розміри й світлу тему оглянуто у браузері; знак у списку застосунків Android 9 не обрізаний. [Звіт релізу](../qa/quest-panel-2026-10-14.md). PNG також доступні в публічному релізі 1.7.6.r6.
