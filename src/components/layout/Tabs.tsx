import { useStore } from '../../state/store';
import type { TabId } from '../../state/slices/uiSlice';

const TABS: { id: TabId; icon: string; label: string }[] = [
  { id: 'tasks', icon: '☀', label: 'Сьогодні' },
  { id: 'planner', icon: '📅', label: 'План' },
  { id: 'inbox', icon: '↓', label: 'Вхідні' },
  { id: 'more', icon: '⋯', label: 'Ще' },
];

export default function Tabs() {
  const currentTab = useStore((s) => s.currentTab);
  const switchTab = useStore((s) => s.switchTab);

  return (
    <div id="tabs">
      {TABS.map((t) => (
        <button
          key={t.id}
          aria-current={currentTab === t.id || (t.id === 'more' && !['tasks', 'planner', 'inbox'].includes(currentTab)) ? 'page' : undefined}
          className={'tb' + (currentTab === t.id || (t.id === 'more' && !['tasks', 'planner', 'inbox'].includes(currentTab)) ? ' active' : '')}
          onClick={() => switchTab(t.id)}
        >
          <span className="tbi">{t.icon}</span>{t.label}
        </button>
      ))}
    </div>
  );
}
