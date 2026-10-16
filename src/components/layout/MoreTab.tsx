import { useStore } from '../../state/store';
import type { TabId } from '../../state/slices/uiSlice';
import Icon from '../common/Icon';

const destinations: { id: TabId; icon: 'quest' | 'note' | 'focus' | 'zones' | 'idea' | 'chart' | 'folder' | 'calendar'; label: string; description: string }[] = [
  { id: 'alltasks', icon: 'quest', label: 'Усі задачі', description: 'Пошук, майбутні, відкладені й виконані справи' },
  { id: 'state', icon: 'focus', label: 'Стан', description: 'Фокус, енергія, пріоритети й повсякденні задачі' },
  { id: 'zones', icon: 'zones', label: 'Зони', description: 'Розклад і контексти дня' },
  { id: 'notefolders', icon: 'folder', label: 'Папки нотаток', description: 'Створення та видалення папок' },
  { id: 'daypresets', icon: 'calendar', label: 'Пресети дня', description: 'Типові задачі, зони й графіки тижня' },
  { id: 'ideas', icon: 'idea', label: 'Ідеї', description: 'Справи, які чекають свого часу' },
  { id: 'stats', icon: 'chart', label: 'Статистика', description: 'Рівні, активні дні й досягнення' },
];

export default function MoreTab() {
  const switchTab = useStore(s => s.switchTab);
  return <section className="tsec active more-menu" aria-label="Усі розділи">
    <h2>Ще</h2>
    {destinations.map(({ id, icon, label, description }) => <button type="button" key={id} aria-label={label} onClick={() => switchTab(id)}>
      <Icon name={icon} /><span><strong>{label}</strong><small>{description}</small></span>
    </button>)}
  </section>;
}
