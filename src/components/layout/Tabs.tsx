import { useStore } from '../../state/store';
import type { TabId } from '../../state/slices/uiSlice';

const TABS: { id: TabId; icon: string; label: string }[] = [
  { id: 'tasks', icon: '✅', label: 'Задачі' },
  { id: 'notes', icon: '📝', label: 'Блокнот' },
  { id: 'planner', icon: '📅', label: 'План' },
  { id: 'state', icon: '🫀', label: 'Стан' },
  { id: 'zones', icon: '🕐', label: 'Зони' },
  { id: 'ideas', icon: '💡', label: 'Ідеї' },
  { id: 'stats', icon: '📊', label: 'Стат' },
];

export default function Tabs() {
  const currentTab = useStore((s) => s.currentTab);
  const switchTab = useStore((s) => s.switchTab);

  return (
    <div id="tabs">
      {TABS.map((t) => (
        <button
          key={t.id}
          className={'tb' + (currentTab === t.id ? ' active' : '')}
          onClick={() => switchTab(t.id)}
        >
          <span className="tbi">{t.icon}</span>{t.label}
        </button>
      ))}
    </div>
  );
}
