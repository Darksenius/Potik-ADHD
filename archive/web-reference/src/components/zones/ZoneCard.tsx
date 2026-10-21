import { useStore } from '../../state/store';
import { useClock } from '../../hooks/useClock';
import { hmToString } from '../../utils/date';
import Icon from '../common/Icon';

export default function ZoneCard() {
  const hm = useClock();
  // Keep this component subscribed to inputs used indirectly by getActiveZones().
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
  const focusShort = currentFocus ? (currentFocus.length > 10 ? currentFocus.slice(0, 10) + '…' : currentFocus) : '';
  const showActiveZones = zones.length > 1 && pz.id !== 0;

  return (
    <div id="zone-card">
      {showActiveZones && (
        <div aria-label={`${zones.length} активні зони`} style={{ display: 'flex', alignItems: 'center', gap: 5, marginBottom: 7 }}>
          {zones.slice(0, 5).map((zone) => (
            <span
              key={zone.id}
              aria-hidden="true"
              style={{ width: 11, height: 11, borderRadius: '50%', background: zone.color, display: 'inline-block', boxShadow: `0 0 4px ${zone.color}` }}
            />
          ))}
          <span style={{ fontSize: 10, color: 'var(--t3)', marginLeft: 3 }}>Активних зон: {zones.length}</span>
        </div>
      )}
      {zones.slice(0, 3).map((z, i) => (
        <div className="zl" key={z.id} style={{ borderLeftColor: z.color }}>
          <div>
            <h3 style={{ color: z.color }}>{z.nm}</h3>
            <p>{z.desc}</p>
            {i === 0 && focusShort && <div style={{ fontSize: 12, color: 'var(--t3)', marginTop: 2 }}><Icon name="focus" /> {focusShort}</div>}
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
