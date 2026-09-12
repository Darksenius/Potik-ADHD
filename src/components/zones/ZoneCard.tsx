import { useEffect } from 'react';
import { useStore } from '../../state/store';
import { useClock } from '../../hooks/useClock';
import { hmToString } from '../../utils/date';

export default function ZoneCard() {
  const hm = useClock();
  // Підписки нижче гарантують перемальовку одразу при зміні даних (не чекаючи
  // наступного тіку годинника) — саме getActiveZones() читає їх усередині.
  useStore((s) => s.zones);
  useStore((s) => s.planDayZones);
  useStore((s) => s.planDayOff);
  useStore((s) => s.planRules);
  useStore((s) => s.dayTemplates);
  const currentFocus = useStore((s) => s.currentFocus);
  const zoneSlotRange = useStore((s) => s.zoneSlotRange);
  const zones = useStore.getState().getActiveZones(hm);
  const pz = zones[0];
  const t = hmToString(hm);

  // document.documentElement.style.setProperty('--z', pz.color) — рядок 1434.
  // ЦЕ Є ТОЙ САМИЙ живий акцентний колір, що визначає --z для ВСЬОГО застосунку
  // (кнопки, іконки тощо), не просто для цієї картки.
  useEffect(() => {
    document.documentElement.style.setProperty('--z', pz.color);
  }, [pz.color]);

  const focusShort = currentFocus ? (currentFocus.length > 10 ? currentFocus.slice(0, 10) + '…' : currentFocus) : '';

  return (
    <div id="zone-card">
      {zones.length > 1 && pz.id !== 0 && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 5, marginBottom: 7 }}>
          {zones.slice(0, 5).map((z) => (
            <span
              key={z.id}
              style={{ width: 11, height: 11, borderRadius: '50%', background: z.color, display: 'inline-block', boxShadow: `0 0 4px ${z.color}` }}
            />
          ))}
          <span style={{ fontSize: 10, color: 'var(--t3)', marginLeft: 3 }}>{zones.length} зони активні</span>
        </div>
      )}
      {zones.slice(0, 3).map((z, i) => (
        <div className="zl" key={z.id} style={{ borderLeftColor: z.color }}>
          <div>
            <h3 style={{ color: z.color }}>{z.nm}</h3>
            <p>{z.desc}</p>
            {i === 0 && focusShort && <div style={{ fontSize: 10, color: 'var(--t3)', marginTop: 2 }}>🧠 {focusShort}</div>}
          </div>
          {i === 0 ? (
            <div id="clock">{t}</div>
          ) : (
            <span className="badge" style={{ borderLeft: `2px solid ${z.color}`, fontFamily: "'Space Mono',monospace" }}>
              {zoneSlotRange(z, hm)}
            </span>
          )}
        </div>
      ))}
    </div>
  );
}
