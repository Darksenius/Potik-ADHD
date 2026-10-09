import { useStore } from '../../state/store';
import type { TabId } from '../../state/slices/uiSlice';

const destinations: [TabId, string, string][] = [
  ['alltasks', 'Усі задачі', 'Пошук, майбутні, відкладені й виконані справи'],
  ['notes', '📝 Блокнот', 'Швидкі нотатки, папки й вільний текст'],
  ['state', '🫀 Стан', 'Фокус, енергія, пріоритети й рутина'],
  ['zones', '🕐 Зони', 'Розклад і контексти дня'],
  ['ideas', '💡 Ідеї', 'Справи, які чекають свого часу'],
  ['stats', '📊 Стат', 'Історія та досягнення'],
];
export default function MoreTab() {
  const switchTab = useStore(s => s.switchTab);
  const showPage = useStore(s => s.showPage);
  return <section className="tsec active more-menu" aria-label="Усі розділи">
    <h2>Ще</h2>
    {destinations.map(([id, label, description]) => <button key={id} onClick={() => switchTab(id)} aria-label={label}><strong>{label}</strong><span>{description}</span></button>)}
    <button onClick={() => showPage('settings')}><strong>Налаштування</strong><span>Вигляд, резервні копії, версія та ліцензія</span></button>
  </section>;
}
