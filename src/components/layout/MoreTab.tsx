import { useStore } from '../../state/store';
import type { TabId } from '../../state/slices/uiSlice';
import Icon from '../common/Icon';

const destinations: { id: TabId; icon: 'quest' | 'note' | 'focus' | 'zones' | 'idea' | 'chart'; label: string; description: string }[] = [
  { id: 'alltasks', icon: 'quest', label: 'Усі задачі', description: 'Пошук, майбутні, відкладені й виконані справи' },
  { id: 'notes', icon: 'note', label: 'Блокнот', description: 'Швидкі нотатки, папки й вільний текст' },
  { id: 'state', icon: 'focus', label: 'Стан', description: 'Фокус, енергія, пріоритети й рутина' },
  { id: 'zones', icon: 'zones', label: 'Зони', description: 'Розклад і контексти дня' },
  { id: 'ideas', icon: 'idea', label: 'Ідеї', description: 'Справи, які чекають свого часу' },
  { id: 'stats', icon: 'chart', label: 'Стат', description: 'Історія та досягнення' },
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
