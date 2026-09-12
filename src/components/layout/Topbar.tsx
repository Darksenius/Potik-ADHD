import { useStore } from '../../state/store';

export default function Topbar() {
  const xp = useStore((s) => s.xp);
  const level = useStore((s) => s.level);

  return (
    <div id="topbar">
      <div id="logo">Потік</div>
      <div className="top-r">
        <div id="xp-badge">⚡ <span id="xpd">{xp}</span></div>
        <div id="lvl-badge">LVL {level}</div>
      </div>
    </div>
  );
}
