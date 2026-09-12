import { useState } from 'react';
import { useStore } from '../../state/store';
import OssPage, { useAppVersion } from '../oss/OssPage';
import { requestAndShowNotification, isNotificationGranted } from '../../services/webNotifications';

export default function ReadmePage() {
  const theme = useStore((s) => s.theme);
  const toggleTheme = useStore((s) => s.toggleTheme);
  const [ossOpen, setOssOpen] = useState(false);
  const version = useAppVersion();

  return (
    <>
      <div id="readme-page" className="page active">
        <div id="rm-hdr">
          <h1>Потік · Довідка</h1>
          <p>
            Застосунок для людей з РДУГ <span style={{ color: 'var(--t3)' }}>{version}</span>
          </p>
        </div>

        <div style={{ background: 'var(--s1)', border: '1px solid var(--b2)', borderRadius: 'var(--r)', padding: 13, margin: '0 0 12px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10 }}>
          <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--t1)' }}>🎨 Оформлення</div>
          <button
            onClick={toggleTheme}
            style={{ background: 'var(--s3)', border: '1px solid var(--b2)', borderRadius: 8, padding: '8px 13px', color: 'var(--t1)', fontFamily: "'Syne',sans-serif", fontSize: 12, fontWeight: 700, cursor: 'pointer' }}
          >
            {theme === 'light' ? '☀️ Світла тема' : '🌙 Темна тема'}
          </button>
        </div>

        <div style={{ background: 'var(--s1)', border: '1px solid var(--b2)', borderRadius: 'var(--r)', padding: 13, margin: '0 0 12px' }}>
          <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--t1)', marginBottom: 6 }}>📌 Сповіщення на пристрої</div>
          <p style={{ fontSize: 11, color: 'var(--t2)', lineHeight: 1.5 }}>
            Постійне сповіщення з поточною зоною, задачами та рутиною з&apos;являється <b>автоматично</b> при запуску — навіть коли застосунок згорнутий. Оновлюється щохвилини. Термінові, заплановані та відкладені задачі прилітають окремими сповіщеннями.
          </p>
          {typeof window !== 'undefined' && !window.Capacitor?.isNativePlatform?.() && (
            <button
              onClick={requestAndShowNotification}
              style={{ marginTop: 8, background: 'var(--s3)', border: '1px solid var(--b2)', borderRadius: 8, padding: '7px 12px', color: 'var(--t1)', fontFamily: "'Syne',sans-serif", fontSize: 11, fontWeight: 700, cursor: 'pointer' }}
            >
              {isNotificationGranted() ? '✓ Сповіщення увімкнено' : '🔔 Увімкнути сповіщення браузера'}
            </button>
          )}
        </div>

        <div className="rm-body">
          <div className="rm-s">
            <h3>🧠 Що таке Потік</h3>
            <p><b style={{ color: 'var(--t1)' }}>Потік — не твій дах, а застосунок для людей з РДУГ.</b> Це не черговий список задач, а система зовнішньої підтримки виконавчих функцій. Відповідає не «що робити?», а «що краще зараз?»</p>
            <p>Три кити: <b style={{ color: 'var(--t1)' }}>Зони дня</b> (час = контекст), <b style={{ color: 'var(--t1)' }}>Гіперфокус</b> (де насправді увага), <b style={{ color: 'var(--t1)' }}>Захоплення імпульсів</b> (вивантажив — повернувся).</p>
          </div>

          <div className="rm-s">
            <h3>❓ Що таке РДУГ</h3>
            <p><b style={{ color: 'var(--t1)' }}>РДУГ</b> — розлад дефіциту уваги та гіперактивності (англ. ADHD). Це не «лінь» і не «брак волі», а нейророзвитковий розлад, при якому нестабільно працюють виконавчі функції: утримання уваги, гальмування імпульсів, відчуття часу, перемикання та запуск дій.</p>
            <div className="rm-tip"><b>Чому Потік:</b> більшість планувальників припускають, що людина сама знає що, коли і як робити. При РДУГ саме ці механізми нестабільні — Потік компенсує їх зовнішньою структурою.</div>
          </div>

          <div className="rm-s">
            <h3>📖 Визначення за DSM-5</h3>
            <p><b>🇺🇦 Українською:</b> Стійкий патерн неуважності та/або гіперактивності-імпульсивності, що заважає функціонуванню чи розвитку. Кілька симптомів присутні до 12 років, проявляються у двох і більше середовищах (дім, навчання, робота) та помітно знижують якість соціального, навчального чи професійного життя.</p>
            <p><b>🇬🇧 English (DSM-5):</b> A persistent pattern of inattention and/or hyperactivity-impulsivity that interferes with functioning or development, with several symptoms present before age 12, occurring in two or more settings, and reducing the quality of social, academic, or occupational functioning.</p>
            <div className="rm-tip">Це довідкова інформація, а не діагноз. Діагностує лише фахівець.</div>
          </div>

          <div className="rm-s">
            <h3>🔀 Вкладки</h3>
            <div className="rm-kb">
              <span className="rmk">✅ Задачі</span><span className="rmd">Список задач, фільтри, додавання, кошик</span>
              <span className="rmk">📝 Блокнот</span><span className="rmd">Швидкі нотатки по папках, вільний блокнот, Експорт/Імпорт</span>
              <span className="rmk">📅 План</span><span className="rmd">Тиждень, аналіз дня, планування задач і зон наперед, шаблони дня</span>
              <span className="rmk">🫀 Стан</span><span className="rmd">Гіперфокус, пріоритети дня, енергія, рутина</span>
              <span className="rmk">🕐 Зони</span><span className="rmd">Часові зони дня з кольором і слотами</span>
              <span className="rmk">💡 Ідеї</span><span className="rmd">Задачі «на колись»</span>
              <span className="rmk">📊 Стат</span><span className="rmd">Серія, досвід, рівень, досягнення</span>
            </div>
          </div>

          <div className="rm-s">
            <h3>⏰ Зони дня</h3>
            <p>Кожна зона — часовий проміжок. Колір всього інтерфейсу змінюється автоматично. У шторці показуються задачі ЛИШЕ поточної зони.</p>
            <p><b style={{ color: 'var(--t1)' }}>🔗 Прив&apos;язані задачі</b> — з&apos;являються при кожному вході в зону («Поїсти» → Обід).</p>
            <p><b style={{ color: 'var(--t1)' }}>У Плані</b> можна призначити часову зону під конкретний день — вона перебиває звичайний розклад саме того дня.</p>
          </div>

          <div className="rm-s">
            <h3>📅 План</h3>
            <p><b style={{ color: 'var(--t1)' }}>Аналіз дня:</b> скільки задач виконано, нотаток додано, переключень гіперфокусу, рівень енергії та рутина — з реальних дій.</p>
            <p><b style={{ color: 'var(--t1)' }}>Планування наперед:</b> «+ Задача» створює справжню задачу на обраний день (з часом «10:00 Назва» стане будильником). «🕐 Зона» призначає часову зону під день.</p>
            <p><b style={{ color: 'var(--t1)' }}>Шаблони дня:</b> збережи набір зон як іменований шаблон і застосовуй до будь-якого дня одним дотиком.</p>
            <p><b style={{ color: 'var(--t1)' }}>Примітка дня:</b> вільний текст-щоденник під кожен день — можна записати спогад навіть про минулий день. Потрапляє в текстовий експорт.</p>
          </div>

          <div className="rm-s">
            <h3>🫀 Стан</h3>
            <p><b style={{ color: 'var(--t1)' }}>Гіперфокус:</b> натискай чіп або вводь вручну. Система пише час переключення і тривалість кожного стану — видно, де насправді була увага.</p>
            <p><b style={{ color: 'var(--t1)' }}>Енергія:</b> досвід нараховується раз на годину (клікати можна скільки завгодно — страховка від випадкового дотику).</p>
            <p><b style={{ color: 'var(--t1)' }}>Рутина:</b> корисна (Вода +100 мл, Розтяжка +2 хв) і шкідлива (знімає досвід — для усвідомлення патернів, без самокритики). У шторці клікабельна.</p>
          </div>

          <div className="rm-s">
            <h3>☑ Типи задач</h3>
            <div className="rm-kb">
              <span className="rmk">☑ Проста</span><span className="rmd">Відмітити. +10 досвіду</span>
              <span className="rmk">≡ Чекліст</span><span className="rmd">Підпункти, кожен +3 досвіду</span>
              <span className="rmk">+N Лічильник</span><span className="rmd">Числовий лічильник з ціллю</span>
              <span className="rmk">✎ З нотаткою</span><span className="rmd">Задача + текстове поле</span>
              <span className="rmk">⏰ Будильник</span><span className="rmd">Окреме сповіщення у вказаний час</span>
              <span className="rmk">📅 Запланована</span><span className="rmd">На дату+час, окреме сповіщення + автонагадування напередодні о 12:00</span>
              <span className="rmk">⏱ Pomodoro</span><span className="rmd">25/5 хв, +15 досвіду за сесію</span>
              <span className="rmk">◉ Звичка</span><span className="rmd">Трекер по днях тижня</span>
              <span className="rmk">★ Дитяча</span><span className="rmd">Зірочки + нагорода</span>
              <span className="rmk">⚠ Шкідлива</span><span className="rmd">Штраф досвіду при тапі</span>
              <span className="rmk">🔗 До зони</span><span className="rmd">Активується при вході в зону</span>
            </div>
          </div>

          <div className="rm-s">
            <h3>⚡ Досвід і гейміфікація</h3>
            <p>Досвід скорочує відстань між дією і нагородою. Рівень росте через кожні N×100 досвіду.</p>
            <p><b style={{ color: 'var(--t1)' }}>Денний ліміт:</b> максимум +250 досвіду за добу. Шкідливі дії знімають досвід і «відкривають» місце, щоб компенсувати втрачене.</p>
          </div>

          <div className="rm-s">
            <h3>⚡ Захоплення імпульсів</h3>
            <p>Думку зловив — вивантажив у Блокнот і повернувся до справи. Папки сортують нотатки (Імпульс, Думка, Терапія…). Швидку нотатку можна додати у вкладці «Блокнот», у віджеті та просто зі шторки сповіщення.</p>
            <p><b>Три крапки:</b> у полі швидкої нотатки «...Назва» створює задачу; самі «...» відкривають вікно нової задачі.</p>
          </div>

          <div className="rm-s">
            <h3>💾 Експорт / Імпорт</h3>
            <p>У Блокноті: <b>Експорт</b> зберігає все у файл .json. <b>Імпорт</b> ДОДАЄ дані з файлу (не замінює наявні — дублі пропускаються).</p>
            <p><b style={{ color: 'var(--t1)' }}>🤖 Аналіз ШІ:</b> кнопка копіює готовий промпт разом із твоїми задачами, зонами й рутиною. Встав це у ChatGPT, Claude чи інший ШІ — і він підкаже пріоритети, розбивку задач і планування. Нічого нікуди не надсилається автоматично — копіюється лише в буфер, ти сам вирішуєш, куди вставити.</p>
          </div>

          <div className="rm-s">
            <h3>📜 Ліцензія та вихідний код</h3>
            <p><b style={{ color: 'var(--t1)' }}>Застосунок з ліцензією AGPL v3</b> — вільне програмне забезпечення: код відкритий, його можна вивчати, змінювати й поширювати на умовах тієї ж ліцензії.</p>
            <button className="oss-btn" onClick={() => setOssOpen(true)}>📜 Ліцензія та вихідний код</button>
          </div>
        </div>
      </div>
      <OssPage open={ossOpen} onClose={() => setOssOpen(false)} />
    </>
  );
}
