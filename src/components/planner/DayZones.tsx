import { useState } from 'react';
import { useStore } from '../../state/store';
import { ruleFor } from '../../state/slices/plannerSlice';
import type { DateKey } from '../../types';

export default function DayZones({ ds }: { ds: DateKey }) {
  const zones = useStore((s) => s.zones);
  const planDayZones = useStore((s) => s.planDayZones);
  const planDayOff = useStore((s) => s.planDayOff);
  const planRules = useStore((s) => s.planRules);
  const dayBlocks = useStore((s) => s.dayBlocks);
  const zoneSortKey = useStore((s) => s.zoneSortKey);
  const addDayZone = useStore((s) => s.addDayZone);
  const removeDayZone = useStore((s) => s.removeDayZone);
  const resetDayToRule = useStore((s) => s.resetDayToRule);
  const setDailyZoneOff = useStore((s) => s.setDailyZoneOff);
  const restoreDailyZone = useStore((s) => s.restoreDailyZone);
  const editDailyZoneForDay = useStore((s) => s.editDailyZoneForDay);
  const addZone = useStore((s) => s.addZone);

  const [addZoneId, setAddZoneId] = useState('');
  const [addStart, setAddStart] = useState('09:00');
  const [addEnd, setAddEnd] = useState('12:00');

  const dayZones = dayBlocks(ds);
  const dayRule = ruleFor(planRules, ds);
  const dayExplicit = !!planDayZones[ds];
  const dayOffArr = planDayOff[ds] || [];

  const dailyZ = zones
    .filter((z) => z.active !== false && !(z.bound && !z.actPin) && (z.slots || []).length && dayOffArr.indexOf(z.id) < 0)
    .slice()
    .sort((a, b) => (zoneSortKey(a) < zoneSortKey(b) ? -1 : 1));

  const zsort = zones.slice().sort((a, b) => (a.active === false ? 1 : 0) - (b.active === false ? 1 : 0));

  return (
    <div style={{ margin: '4px 0 8px' }}>
      <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--t3)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 5 }}>
        🕐 Часові зони дня
      </div>

      {dayExplicit && dayRule && (
        <div style={{ fontSize: 10, color: 'var(--t3)', padding: '1px 0 4px' }}>
          ✍ ручні правки ·{' '}
          <span onClick={() => resetDayToRule(ds)} style={{ color: 'var(--z)', cursor: 'pointer', textDecoration: 'underline' }}>
            повернути графік «{dayRule.name}»
          </span>
        </div>
      )}
      {!dayExplicit && dayRule && !!dayZones.length && (
        <div style={{ fontSize: 10, color: 'var(--t3)', padding: '1px 0 4px' }}>
          📋 за графіком «{dayRule.name}» — правка створить виняток лише для цього дня
        </div>
      )}

      {dailyZ.map((z) => (
        <div className="plan-task-row" style={{ borderLeft: `3px solid ${z.color}` }} key={z.id}>
          <span className="plan-task-nm">
            {z.nm} <span style={{ fontSize: 9, color: 'var(--t3)', fontWeight: 400 }}>∞ щодня</span>
          </span>
          <span style={{ fontSize: 10, color: 'var(--t3)', fontFamily: 'monospace' }}>
            {(z.slots || []).map((sl) => sl.s + '–' + sl.e).join(' · ')}
          </span>
          <button className="plan-rm" title="Змінити час лише цього дня" onClick={() => editDailyZoneForDay(ds, z.id)}>✎</button>
          <button className="plan-rm" title="Вимкнути лише цього дня" onClick={() => setDailyZoneOff(ds, z.id)}>✕</button>
        </div>
      ))}

      {dayOffArr.map((zid) => {
        const z = zones.find((zz) => zz.id === zid);
        if (!z) return null;
        return (
          <div key={zid} style={{ fontSize: 10, color: 'var(--t3)', padding: '2px 0 2px 8px' }}>
            💤 {z.nm} вимкнена цього дня ·{' '}
            <span onClick={() => restoreDailyZone(ds, zid)} style={{ color: 'var(--z)', cursor: 'pointer', textDecoration: 'underline' }}>
              повернути
            </span>
          </div>
        );
      })}

      {dayZones.map((b, i) => {
        const z = zones.find((zz) => zz.id === b.zoneId);
        return (
          <div className="plan-task-row" style={{ borderLeft: `3px solid ${z ? z.color : '#888'}` }} key={i}>
            <span className="plan-task-nm">
              {z ? z.nm : '?'}
              {b.ov && <span style={{ fontSize: 9, color: 'var(--t3)', fontWeight: 400 }}> лише цей день</span>}
            </span>
            <span style={{ fontSize: 10, color: 'var(--t3)', fontFamily: 'monospace' }}>{b.s}–{b.e}</span>
            <button className="plan-rm" onClick={() => removeDayZone(ds, i)}>✕</button>
          </div>
        );
      })}

      <div style={{ display: 'flex', gap: 5, alignItems: 'center', marginTop: 5, flexWrap: 'wrap' }}>
        <select
          value={addZoneId}
          onChange={(e) => setAddZoneId(e.target.value)}
          style={{ background: 'var(--s3)', border: '1px solid var(--b2)', borderRadius: 6, color: 'var(--t1)', fontSize: 11, padding: '4px 6px', fontFamily: "'Syne',sans-serif" }}
        >
          <option value="">— зона —</option>
          {zsort.map((z) => (
            <option value={z.id} key={z.id}>{z.nm}{z.active === false ? ' 💤' : ''}</option>
          ))}
        </select>
        <input type="time" value={addStart} onChange={(e) => setAddStart(e.target.value)} style={{ background: 'var(--s3)', border: '1px solid var(--b2)', borderRadius: 6, color: 'var(--t1)', fontSize: 11, padding: '3px 5px' }} />
        <input type="time" value={addEnd} onChange={(e) => setAddEnd(e.target.value)} style={{ background: 'var(--s3)', border: '1px solid var(--b2)', borderRadius: 6, color: 'var(--t1)', fontSize: 11, padding: '3px 5px' }} />
        <button className="pdd-add" onClick={() => addZoneId && addDayZone(ds, Number(addZoneId), addStart, addEnd)}>+ Зона</button>
        <button
          className="pdd-add"
          style={{ background: 'var(--s3)', color: 'var(--t2)', border: '1px solid var(--b2)' }}
          onClick={() => {
            const nm = window.prompt('Назва нової зони:');
            if (nm && nm.trim()) addZone(nm.trim());
          }}
        >
          ＋ Нова зона
        </button>
      </div>
    </div>
  );
}
