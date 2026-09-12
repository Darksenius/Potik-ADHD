import { useStore } from '../../state/store';
import type { Task } from '../../types';
import { TASK_TYPE_LABELS } from '../../constants';
import TaskBody from './TaskBody';

const TXP: Record<string, number> = {
  simple: 10, check: 10, counter: 2, note: 5, alarm: 10, sched: 10,
  timewin: 5, pomodoro: 10, habit: 8, kid: 10, ctx: 8, negative: 0, zonelinked: 8,
};

function repeatLabel(t: Task): string {
  if (!t.repeat || t.repeat === 'none') return '';
  if (t.repeat === 'interval') {
    const suffix = t.repeatUnit === 'hour' ? 'год' : t.repeatUnit === 'day' ? 'дн' : t.repeatUnit === 'week' ? 'тиж' : t.repeatUnit === 'month' ? 'міс' : 'хв';
    return '↺ ' + (t.repeatInterval || 30) + suffix;
  }
  return '↺';
}

export default function TaskItem({ t, isPriority }: { t: Task; isPriority: boolean }) {
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

  return (
    <div
      className={'tc' + (t.done ? ' done-t' : '') + (isNeg ? ' neg-tc' : '') + (isPriority ? ' prio-task' : '')}
      style={{ borderLeftColor: t.zoneColor || 'transparent' }}
    >
      <div className="th">
        <button
          className={'ck' + (t.done && !isNeg ? ' on' : '') + (isNeg ? ' neg-ck' : '')}
          onClick={() => toggleTask(t.id)}
          style={{ marginTop: 2, flexShrink: 0 }}
        >
          {!isNeg && t.done ? '✓' : ''}
        </button>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="tt" onClick={hasBody ? () => toggleExpanded(t.id) : undefined}>{t.title}</div>
        </div>
        {hasBody && (
          <button className="ab ghost" onClick={() => toggleExpanded(t.id)} style={{ flexShrink: 0, marginTop: 1 }}>
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
        {isNeg ? (
          <span style={{ fontSize: 10, color: 'var(--neg)', fontFamily: "'Space Mono',monospace", flexShrink: 0 }}>−{t.negXp || 5}</span>
        ) : (
          <span style={{ fontSize: 10, color: 'var(--xp)', fontFamily: "'Space Mono',monospace", flexShrink: 0 }}>+{TXP[t.type] ?? 10}</span>
        )}
        <div className="tc-acts">
          <button className="ab" onClick={() => moveTask(t.id, -1)} title="Вгору" style={{ color: 'var(--t3)' }}>↑</button>
          <button className="ab" onClick={() => moveTask(t.id, 1)} title="Вниз" style={{ color: 'var(--t3)' }}>↓</button>
          <button className="ab eb" onClick={() => requestEditTask(t.id)} title="✎">✎</button>
          <button className="ab" onClick={() => toSomeday(t.id)} title="📦" style={{ color: 'var(--t3)' }}>📦</button>
          <button className="ab del" onClick={() => deleteTask(t.id)} title="✕">✕</button>
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
