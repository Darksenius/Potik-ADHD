import { useState } from 'react';
import { useStore } from '../../state/store';
import { fmtDate } from '../../utils/date';
import '../../styles/daily-insights.css';

export default function DailyInsightCalendar() {
  const days = useStore(state => state.progressDays);
  const claims = useStore(state => state.dailyInsightClaims);
  const open = useStore(state => state.openDailyInsight);
  const today = fmtDate(new Date());
  const [month, setMonth] = useState(today.slice(0, 7));
  const [year, number] = month.split('-').map(Number);
  const first = new Date(year, number - 1, 1, 12);
  const length = new Date(year, number, 0).getDate();
  const padding = (first.getDay() + 6) % 7;
  const active = new Set(days);
  const earliest = (days[0] || today).slice(0, 7);
  const shift = (delta: number) => setMonth(fmtDate(new Date(year, number - 1 + delta, 1, 12)).slice(0, 7));
  return <section className="insight-calendar" aria-labelledby="insight-calendar-title">
    <div className="insight-calendar-heading"><div><h3 id="insight-calendar-title">Думки за активні дні</h3>
      <p>Одна нова думка за день із дією. Пропуск не скидає твій шлях.</p></div>
      <span>{Math.min(100, new Set(claims.map(claim => claim.cardId)).size)} / 100</span></div>
    <div className="insight-month-nav"><button type="button" aria-label="Попередній місяць думок" disabled={month <= earliest} onClick={() => shift(-1)}>←</button>
      <strong>{first.toLocaleDateString('uk', { month: 'long', year: 'numeric' })}</strong>
      <button type="button" aria-label="Наступний місяць думок" disabled={month >= today.slice(0, 7)} onClick={() => shift(1)}>→</button></div>
    <div className="insight-weekdays" aria-hidden="true">{['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Нд'].map(day => <span key={day}>{day}</span>)}</div>
    <div className="insight-day-grid" aria-label="Календар активності">
      {Array.from({ length: padding }, (_, index) => <span key={'pad' + index} />)}
      {Array.from({ length }, (_, index) => {
        const day = `${month}-${String(index + 1).padStart(2, '0')}`;
        const claim = claims.find(item => item.day === day);
        return <button type="button" key={day} data-day={day} disabled={day > today}
          className={'insight-day' + (active.has(day) ? ' is-active' : '') + (day === today ? ' is-today' : '')}
          aria-label={`${day}: ${claim ? `думка ${claim.sequence}` : active.has(day) ? 'активний день' : 'без активності'}`}
          onClick={() => open(day)}><span>{index + 1}</span><small>{claim ? '✦' : active.has(day) ? '•' : '·'}</small></button>;
      })}
    </div>
    <p className="insight-calendar-hint">Натисни дату, щоб перечитати думку. Порожні дні нічого не віднімають. Після сотні думки повторюються новим колом.</p>
  </section>;
}
