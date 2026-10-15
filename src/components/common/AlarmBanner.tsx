import { useStore } from '../../state/store';

export default function AlarmBanner() {
  const ids = useStore(s => s.alarmTaskIds);
  const tasks = useStore(s => s.tasks);
  if (!ids.length) return null;
  return <div id="alarm-ring" className="show" role="alert">
    <span>{ids.map(id => tasks.find(t => t.id === id)?.title).filter(Boolean).join(' · ')}</span>
    <button onClick={() => useStore.setState({ alarmTaskIds: [] })}>Зрозуміло</button>
  </div>;
}
