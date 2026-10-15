import { useStore } from '../../state/store';
import Icon from '../common/Icon';

export default function Topbar() {
  const xp = useStore((s) => s.xpTotal);
  const level = useStore((s) => s.level);
  const showXp = useStore((s) => s.preferences.showGamification !== false);

  return (
    <div id="topbar">
      <div id="logo"><img className="potik-brand" src="/brand/potik-mark.png" alt="" />Потік</div>
      {showXp && <div className="top-r">
        <div id="xp-badge"><Icon name="chart" /> <span id="xpd">{xp}</span></div>
        <div id="lvl-badge">LVL {level}</div>
      </div>}
    </div>
  );
}
