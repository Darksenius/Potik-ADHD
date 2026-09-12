import { useEffect } from 'react';
import { useStore } from './state/store';
import Nav from './components/layout/Nav';
import Topbar from './components/layout/Topbar';
import ProgressBars from './components/layout/ProgressBars';
import Tabs from './components/layout/Tabs';
import ZoneCard from './components/zones/ZoneCard';
import TasksTab from './components/tasks/TasksTab';
import NotesTab from './components/notes/NotesTab';
import StateTab from './components/focus/StateTab';
import ZonesTab from './components/zones/ZonesTab';
import IdeasTab from './components/tasks/IdeasTab';
import StatsTab from './components/stats/StatsTab';
import TaskEditor from './components/tasks/TaskEditor';
import PlannerTab from './components/planner/PlannerTab';
import ReadmePage from './components/oss/ReadmePage';
import Toast from './components/common/Toast';
import BackupBanner from './components/common/BackupBanner';
import { usePomodoroTick } from './hooks/usePomodoroTick';

/** applyTheme() — рядки 1165–1170: data-theme на <html> тепер ефект, не імперативний виклик. */
function useThemeEffect() {
  const theme = useStore((s) => s.theme);
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme === 'light' ? 'light' : 'dark');
  }, [theme]);
}

function TabPlaceholder({ name }: { name: string }) {
  return (
    <div className="tsec active" style={{ padding: '24px 4px', textAlign: 'center', color: 'var(--t3)', fontSize: 12 }}>
      «{name}» ще не перенесено на Кроці 3 — у прогресі.
    </div>
  );
}

export default function App() {
  useThemeEffect();
  usePomodoroTick();
  const currentPage = useStore((s) => s.currentPage);
  const currentTab = useStore((s) => s.currentTab);

  return (
    <>
      <Nav />

      {currentPage === 'readme' && <ReadmePage />}

      {/* 'widget' — у ОРИГІНАЛІ теж недосяжна сторінка: goToWidget() ніде не
          викликається і сама сторінка мала inline style="display:none!important".
          Мертвий код оригіналу, не пропущена функція — свідомо не будую UI. */}
      {currentPage !== 'main' && currentPage !== 'readme' && (
        <div className="page active" style={{ padding: 24, textAlign: 'center', color: 'var(--t3)' }}>
          Сторінка «{currentPage}» недосяжна (те саме й в оригіналі — goToWidget() ніде не викликається).
        </div>
      )}

      {currentPage === 'main' && (
        <div id="main-page" className="page active">
          <div id="app">
            <Topbar />
            <ZoneCard />
            <ProgressBars />
            <Tabs />

            {currentTab === 'tasks' && <TasksTab />}
            {currentTab === 'notes' && <NotesTab />}
            {currentTab === 'planner' && <PlannerTab />}
            {currentTab === 'state' && <StateTab />}
            {currentTab === 'zones' && <ZonesTab />}
            {currentTab === 'ideas' && <IdeasTab />}
            {currentTab === 'stats' && <StatsTab />}
          </div>
        </div>
      )}

      <TaskEditor />
      <Toast />
      <BackupBanner />
    </>
  );
}
