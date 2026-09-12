import { useState } from 'react';
import { useStore } from '../../state/store';
import { fmtHuman } from '../../utils/date';
import { PLAN_TEMPLATES } from '../../constants';
import type { DateKey } from '../../types';
import { DayAnalysis, DayJournal } from './DayJournal';
import DayZones from './DayZones';
import DayTemplates from './DayTemplates';

export default function DayDetail({ ds }: { ds: DateKey }) {
  const dayIsRest = useStore((s) => s.dayIsRest);
  const planSchedules = useStore((s) => s.planSchedules);
  const setPlanSchedule = useStore((s) => s.setPlanSchedule);
  const planDayLog = useStore((s) => s.planDayLog);
  const setDayNote = useStore((s) => s.setDayNote);
  const toggleRestDay = useStore((s) => s.toggleRestDay);
  const tasks = useStore((s) => s.tasks);
  const toggleTask = useStore((s) => s.toggleTask);
  const deleteTask = useStore((s) => s.deleteTask);
  const planItems = useStore((s) => s.planItems);
  const togglePlanItem = useStore((s) => s.togglePlanItem);
  const removePlanItem = useStore((s) => s.removePlanItem);
  const addPlanItem = useStore((s) => s.addPlanItem);
  const addPlanEvent = useStore((s) => s.addPlanEvent);

  const [noteText, setNoteText] = useState(planDayLog[ds]?.note || '');

  const today = new Date();
  const todayStr = today.getFullYear() + '-' + String(today.getMonth() + 1).padStart(2, '0') + '-' + String(today.getDate()).padStart(2, '0');
  const isPast = ds < todayStr;
  const isFuture = ds > todayStr;
  const isRest = dayIsRest(ds);
  const sched = planSchedules[ds] || '';
  const items = planItems[ds] || [];
  const dayTasks = tasks.filter((t) => t.planDate === ds && !t.trashed);

  return (
    <div className="plan-detail open">
      <div className="pdd-head">
        <span className="pdd-date">{fmtHuman(new Date(ds + 'T12:00:00'))}{isPast ? ' · минуле' : isFuture ? ' · план' : ' · сьогодні'}</span>
        <div style={{ display: 'flex', gap: 5 }}>
          <button
            className="pdd-add"
            style={{ background: 'var(--s3)', color: 'var(--t2)', border: '1px solid var(--b2)' }}
            onClick={() => {
              const v = window.prompt('Задача на цей день (можна почати з "ГГ:ХХ Назва"):');
              if (v && v.trim()) addPlanItem(ds, v.trim());
            }}
          >
            + Задача
          </button>
          <button
            className="pdd-add"
            onClick={() => {
              const v = window.prompt('Подія (можна почати з "ГГ:ХХ Назва"):');
              if (v && v.trim()) addPlanEvent(ds, v.trim());
            }}
          >
            🎉 Подія
          </button>
        </div>
      </div>

      <DayAnalysis ds={ds} isFuture={isFuture} />
      <DayJournal ds={ds} />

      <div style={{ margin: '4px 0 9px' }}>
        <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--t3)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 5 }}>
          📝 Примітка дня
        </div>
        <textarea
          value={noteText}
          onChange={(e) => {
            setNoteText(e.target.value);
            setDayNote(ds, e.target.value);
          }}
          placeholder="Запиши, що згадав про цей день…"
          style={{ width: '100%', minHeight: 70, background: 'var(--s2)', border: '1px solid var(--b2)', borderRadius: 8, padding: '9px 11px', color: 'var(--t1)', fontFamily: "'Syne',sans-serif", fontSize: 12, lineHeight: 1.55, resize: 'vertical', outline: 'none' }}
        />
      </div>

      <div className="rest-toggle" onClick={() => toggleRestDay(ds)}>
        <span>🌙</span>
        <span className="rt-lbl">Вихідний день</span>
        <div className={'rt-sw' + (isRest ? ' on' : '')} />
      </div>

      {isRest ? (
        <div style={{ fontSize: 11, color: 'rgba(189,16,224,.7)', padding: '3px 0 6px' }}>📋 Нагадування: заплануй тиждень</div>
      ) : (
        <>
          <div className="plan-type-row">
            <div className={'ptype' + (sched === '' ? ' act' : '')} onClick={() => setPlanSchedule(ds, '')} title="Без шаблону">Довільний</div>
            {PLAN_TEMPLATES.map((t) => (
              <div className={'ptype' + (sched === t.id ? ' act' : '')} key={t.id} onClick={() => setPlanSchedule(ds, t.id)} title={t.label}>
                {t.label}
              </div>
            ))}
          </div>
          {sched && (() => {
            const tmpl = PLAN_TEMPLATES.find((t) => t.id === sched);
            return tmpl ? <div style={{ fontSize: 10, color: 'var(--z)', padding: '2px 0 6px' }}>📋 Графік: {tmpl.label} ({tmpl.hours})</div> : null;
          })()}
          <DayZones ds={ds} />
          <DayTemplates ds={ds} />
        </>
      )}

      {dayTasks.map((t) => (
        <div className="plan-task-row" key={t.id}>
          <button className={'plan-ck' + (t.done ? ' on' : '')} onClick={() => toggleTask(t.id)}>{t.done ? '✓' : ''}</button>
          <span className="plan-task-nm" style={t.done ? { textDecoration: 'line-through', opacity: 0.5 } : undefined}>{t.title}</span>
          {t.schedTime && <span style={{ fontSize: 10, color: 'var(--z)', fontFamily: 'monospace' }}>{t.schedTime}</span>}
          <button className="plan-rm" onClick={() => deleteTask(t.id)}>✕</button>
        </div>
      ))}

      {items.map((it, i) => (
        <div className={'plan-task-row' + (it.isEvent ? ' plan-event' : '')} key={i}>
          <button className={'plan-ck' + (it.done ? ' on' : '')} onClick={() => togglePlanItem(ds, i)}>
            {it.done ? '✓' : it.isEvent ? '🎉' : ''}
          </button>
          <span className="plan-task-nm" style={it.done ? { textDecoration: 'line-through', opacity: 0.5 } : undefined}>{it.title || ''}</span>
          {it.time && <span style={{ fontSize: 10, color: 'var(--t3)', fontFamily: 'monospace' }}>{it.time}</span>}
          <button className="plan-rm" onClick={() => removePlanItem(ds, i)}>✕</button>
        </div>
      ))}

      {!dayTasks.length && !items.length && <div className="plan-empty">Порожньо — додай задачу, зону або подію</div>}
    </div>
  );
}
