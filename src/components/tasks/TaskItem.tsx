import { useStore } from '../../state/store';
import type { Task } from '../../types';
import { TASK_TYPE_LABELS } from '../../constants';
import TaskBody from './TaskBody';
import { taskPlanDate } from '../../utils/taskSchedule';
import { fmtDate } from '../../utils/date';

const TXP: Record<string, number> = {
  simple: 10, check: 10, counter: 2, note: 5, alarm: 10, sched: 10,
  timewin: 5, ctx: 8, negative: 0, zonelinked: 8,
};

function repeatLabel(t: Task): string {
  if (!t.repeat || t.repeat === 'none') return '';
  if (t.repeat === 'interval') {
    const suffix = t.repeatUnit === 'hour' ? 'год' : t.repeatUnit === 'day' ? 'дн' : t.repeatUnit === 'week' ? 'тиж' : t.repeatUnit === 'month' ? 'міс' : 'хв';
    return '↺ ' + (t.repeatInterval || 30) + suffix;
  }
  return '↺';
}

export default function TaskItem({ t, isPriority, visibleIds, allowMove = true }: { t: Task; isPriority: boolean; visibleIds: number[]; allowMove?: boolean }) {
  const toggleTask = useStore((s) => s.toggleTask);
  const toggleExpanded = useStore((s) => s.toggleExpanded);
  const moveTask = useStore((s) => s.moveTask);
  const deleteTask = useStore((s) => s.deleteTask);
  const toSomeday = useStore((s) => s.toSomeday);
  const requestEditTask = useStore((s) => s.requestEditTask);
  const folders = useStore((s) => s.folders);

  const isNeg = t.type === 'negative';
  const hasBody = t.type !== 'simple';
  const folder = t.folderId ? folders.find((f) => f.id === t.folderId) : null;
  const rep = repeatLabel(t);
  const date = taskPlanDate(t);
  const showXp = useStore(s => s.preferences.showGamification !== false);

  return (
    <div
      className={'tc' + (t.done ? ' done-t' : '') + (isNeg ? ' neg-tc' : '') + (isPriority ? ' prio-task' : '')}
      style={{ borderLeftColor: t.zoneColor || 'transparent' }}
    >
      <div className="th">
        <button
          className={'ck' + (t.done && !isNeg ? ' on' : '') + (isNeg ? ' neg-ck' : '')}
          onClick={() => toggleTask(t.id)}
          aria-label={(t.done ? 'Позначити невиконаною: ' : 'Виконати: ') + t.title}
          aria-pressed={t.done}
          style={{ marginTop: 2, flexShrink: 0 }}
        >
          {!isNeg && t.done ? '✓' : ''}
        </button>
        <div style={{ flex: 1, minWidth: 0 }}>
          <button className="tt task-title-button" onClick={() => requestEditTask(t.id)}>{t.title}</button>
          <div className="task-schedule-label">
            {t.someday ? 'Колись' : date ? `${date < fmtDate(new Date()) && !t.done ? 'Прострочено · ' : ''}${new Date(date + 'T12:00:00').toLocaleDateString('uk', { day: 'numeric', month: 'short' })}${t.type === 'sched' && t.schedTime ? ' · ' + t.schedTime : ''}` : 'Без дати'}
            {t.snoozeUntil && t.snoozeUntil > Date.now() ? ' · відкладено до ' + new Date(t.snoozeUntil).toLocaleString('uk', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : ''}
            {t.type === 'timewin' ? ` · ${t.windowStart}–${t.windowEnd}` : ''}
          </div>
        </div>
        {hasBody && (
          <button className="ab ghost" aria-label={(t.expanded ? 'Згорнути: ' : 'Розгорнути: ') + t.title} aria-expanded={t.expanded} onClick={() => toggleExpanded(t.id)} style={{ flexShrink: 0, marginTop: 1 }}>
            {t.expanded ? '▲' : '▼'}
          </button>
        )}
      </div>
      <div className="tc-meta">
        {t.zoneName && <span className="badge zbadge" style={{ borderLeftColor: t.zoneColor || undefined }}>{t.zoneName}</span>}
        {t.type === 'zonelinked' && !t.zoneId && (
          <span className="badge" style={{ background: 'rgba(226,75,74,.12)', color: 'var(--neg)', border: '1px solid rgba(226,75,74,.25)' }} title="Не вказано зону">! зона</span>
        )}
        {t.type === 'alarm' && t.alarmTime && <span className={'badge ab-b' + (t.alarmFired ? ' fired' : '')}>{t.alarmTime}</span>}
        {rep && <span className="badge rep-b">{rep}</span>}
        <span className="badge" style={{ background: 'var(--s2)' }}>{TASK_TYPE_LABELS[t.type] || t.type}</span>
        {!!(t.tags || []).length && <span className="badge" style={{ color: 'var(--z)' }}>#{t.tags[0]}</span>}
        {folder && <span className="badge" style={{ background: 'var(--s2)' }} title={folder.nm}>{folder.ico || '📁'}</span>}
        {isNeg && <span className="badge" style={{ background: 'rgba(226,75,74,.12)', color: 'var(--neg)' }}>−{t.negXp || 5} досвіду</span>}
        {showXp && (isNeg ? (
          <span style={{ fontSize: 10, color: 'var(--neg)', fontFamily: "'Space Mono',monospace", flexShrink: 0 }}>−{t.negXp || 5}</span>
        ) : (
          <span style={{ fontSize: 10, color: 'var(--xp)', fontFamily: "'Space Mono',monospace", flexShrink: 0 }}>+{TXP[t.type] ?? 10}</span>
        ))}
        <div className="tc-acts">
          {allowMove && <><button className="ab" onClick={() => moveTask(t.id, -1, visibleIds)} title="Вгору" aria-label={'Підняти: ' + t.title}>↑</button><button className="ab" onClick={() => moveTask(t.id, 1, visibleIds)} title="Вниз" aria-label={'Опустити: ' + t.title}>↓</button></>}
          <button className="ab eb" onClick={() => requestEditTask(t.id)} title="Редагувати" aria-label={'Редагувати: ' + t.title}>✎</button>
          <button className="ab" onClick={() => toSomeday(t.id)} title="На колись" aria-label={'На колись: ' + t.title}>📦</button>
          <button className="ab del" onClick={() => deleteTask(t.id)} title="У кошик" aria-label={'У кошик: ' + t.title}>✕</button>
        </div>
      </div>
      {hasBody && (
        <div className={'tb-body' + (t.expanded ? ' open' : '')}>
          {t.expanded && <TaskBody t={t} />}
        </div>
      )}
    </div>
  );
}
