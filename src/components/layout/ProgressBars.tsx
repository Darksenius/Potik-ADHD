import { useStore } from '../../state/store';
import { useClock } from '../../hooks/useClock';

export default function ProgressBars() {
  const hm = useClock();
  const xp = useStore((s) => s.xp);
  const level = useStore((s) => s.level);

  const dayPct = Math.round(((hm.h * 60 + hm.m) / 1440) * 100);
  const xpNeeded = level * 100; // XPL(level) — рядок 1547
  const xpPct = xpNeeded ? Math.round((xp / xpNeeded) * 100) : 0;

  return (
    <div id="prog-wrap">
      <div className="lrow">
        <span>Прогрес дня</span>
        <span className="pv" id="dpct">{dayPct}%</span>
      </div>
      <div className="ptrack">
        <div className="pfill" id="dfill" style={{ width: dayPct + '%' }} />
      </div>
      <div style={{ marginTop: 7 }}>
        <div className="lrow">
          <span>Досвід до рівня</span>
          <span className="pv" style={{ color: 'var(--xp)' }} id="xppct">{xp}/{xpNeeded}</span>
        </div>
        <div className="ptrack">
          <div className="pfill" id="xp-fill" style={{ width: xpPct + '%' }} />
        </div>
      </div>
    </div>
  );
}
