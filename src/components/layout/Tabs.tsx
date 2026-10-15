import { useStore } from '../../state/store';
import type { TabId } from '../../state/slices/uiSlice';
import Icon from '../common/Icon';

const TABS: { id: TabId; icon: 'quest' | 'calendar' | 'inbox' | 'more'; label: string }[] = [
  { id: 'tasks', icon: 'quest', label: 'Зараз' },
  { id: 'planner', icon: 'calendar', label: 'План' },
  { id: 'inbox', icon: 'inbox', label: 'Вхідні' },
  { id: 'more', icon: 'more', label: 'Ще' },
];

export default function Tabs() {
  const currentTab = useStore((s) => s.currentTab);
  const switchTab = useStore((s) => s.switchTab);

  return (
    <div id="tabs" role="group" aria-label="Розділи Потоку">
      {TABS.map((t) => {
        const active = currentTab === t.id || (t.id === 'more' && !['tasks', 'planner', 'inbox'].includes(currentTab));
        return <button
          type="button"
          key={t.id}
          aria-current={active ? 'page' : undefined}
          className={'tb' + (active ? ' active' : '')}
          onClick={() => switchTab(t.id)}
        >
          <span className="tbi"><Icon name={t.icon} /></span>{t.label}
        </button>;
      })}
    </div>
  );
}
