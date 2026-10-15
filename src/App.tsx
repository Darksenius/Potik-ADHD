import { useEffect } from 'react';
import { useStore } from './state/store';
import Nav from './components/layout/Nav';
import Topbar from './components/layout/Topbar';
import ProgressBars from './components/layout/ProgressBars';
import Tabs from './components/layout/Tabs';
import ZoneCard from './components/zones/ZoneCard';
import TasksTab from './components/tasks/TasksTab';
import NotesTab from './components/notes/NotesTab';
import NoteFoldersTab from './components/notes/NoteFoldersTab';
import SharedCaptureDialog from './components/notes/SharedCaptureDialog';
import TaskUndoNotice from './components/common/TaskUndoNotice';
import StateTab from './components/focus/StateTab';
import ZonesTab from './components/zones/ZonesTab';
import IdeasTab from './components/tasks/IdeasTab';
import StatsTab from './components/stats/StatsTab';
import TaskEditor from './components/tasks/TaskEditor';
import PlannerTab from './components/planner/PlannerTab';
import DayPresetsTab from './components/planner/DayPresetsTab';
import ReadmePage from './components/oss/ReadmePage';
import Toast from './components/common/Toast';
import BackupBanner from './components/common/BackupBanner';
import KbdOverlay from './components/common/KbdOverlay';
import AlarmBanner from './components/common/AlarmBanner';
import MoreTab from './components/layout/MoreTab';
import SettingsPage from './components/layout/SettingsPage';
import WhatsNewPage from './components/layout/WhatsNewPage';
import InboxTab from './components/tasks/InboxTab';
import ZoneAccent from './components/layout/ZoneAccent';
import RewardFeedback from './components/common/RewardFeedback';
import ImpulseReview from './components/common/ImpulseReview';

/** applyTheme() — рядки 1165–1170: data-theme на <html> тепер ефект, не імперативний виклик. */
function useThemeEffect() {
  const theme = useStore((s) => s.theme);
  const preferences = useStore(s => s.preferences);
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: light)');
    const apply = () => document.documentElement.setAttribute('data-theme', preferences.themeMode === 'system' ? mq.matches ? 'light' : 'dark' : preferences.themeMode || theme);
    apply();
    // addListener is required by the Android 9 WebView used in r4 verification.
    mq.addListener(apply);
    document.documentElement.setAttribute('data-reduce-motion', String(!!preferences.reduceMotion));
    return () => mq.removeListener(apply);
  }, [theme, preferences.themeMode, preferences.reduceMotion]);
}

export default function App() {
  useThemeEffect();
  const currentPage = useStore((s) => s.currentPage);
  const currentTab = useStore((s) => s.currentTab);
  const editorTaskId = useStore((s) => s.editorTaskId);

  return (
    <>
      <ZoneAccent />
      <Nav />

      {currentPage === 'readme' && <ReadmePage />}
      {currentPage === 'settings' && <SettingsPage />}
      {currentPage === 'whatsnew' && <WhatsNewPage />}

      {currentPage === 'main' && (
        <div id="main-page" className="page active">
          <div id="app">
            <Topbar />
            <Tabs />
            {currentTab === 'tasks' && <><ZoneCard /><details className="today-context"><summary>Час і досвід</summary><ProgressBars /></details></>}

            {currentTab === 'tasks' && <TasksTab />}
            {currentTab === 'notes' && <NotesTab />}
            {currentTab === 'notefolders' && <NoteFoldersTab />}
            {currentTab === 'planner' && <PlannerTab />}
            {currentTab === 'daypresets' && <DayPresetsTab />}
            {currentTab === 'state' && <StateTab />}
            {currentTab === 'zones' && <ZonesTab />}
            {currentTab === 'ideas' && <IdeasTab />}
            {currentTab === 'stats' && <StatsTab />}
            {currentTab === 'more' && <MoreTab />}
            {currentTab === 'inbox' && <InboxTab />}
            {currentTab === 'alltasks' && <TasksTab key="all" all />}
          </div>
        </div>
      )}

      {editorTaskId !== null && <TaskEditor key={editorTaskId} />}
      <AlarmBanner />
      <Toast />
      <RewardFeedback />
      <ImpulseReview />
      <BackupBanner />
      <KbdOverlay />
      <SharedCaptureDialog />
      <TaskUndoNotice />
    </>
  );
}
