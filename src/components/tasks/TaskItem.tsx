import { useStore } from '../../state/store';
import type { Task } from '../../types';
import { TASK_TYPE_LABELS } from '../../constants';
import TaskBody from './TaskBody';
import TaskCounterControls from './TaskCounterControls';
import Icon from '../common/Icon';
import { taskPlanDate } from '../../utils/taskSchedule';
import { questDeferralLabel } from '../../utils/questPolicy';
import { fmtDate } from '../../utils/date';
import { COUNTER_UNITS, hasTaskCounter, taskCounterTarget } from '../../utils/taskCounter';
import type { TaskAvailabilityExplanation } from '../../utils/taskAvailabilityLabel';
import type { MouseEvent } from 'react';
import { runTaskCompletionWithReward } from '../../utils/taskRewardFeedback';

const TXP: Record<string, number> = {
  simple: 10, check: 10, counter: 2, note: 5, alarm: 10, sched: 10,
  timewin: 5, ctx: 8, negative: 0, zonelinked: 8,
};

function repeatLabel(t: Task): string {
  if (!t.repeat || t.repeat === 'none') return '';
  if (t.repeat === 'interval') {
    const suffix = t.repeatUnit === 'sec' ? 'с' : t.repeatUnit === 'hour' ? 'год' : t.repeatUnit === 'day' ? 'дн' : t.repeatUnit === 'week' ? 'тиж' : t.repeatUnit === 'month' ? 'міс' : 'хв';
    return '↺ ' + (t.repeatInterval || 30) + suffix;
  }
  return '↺';
}

export default function TaskItem({
  t, isPriority, visibleIds, allowMove = true, questCard = false, canDefer = false, allView = false, availabilityMessage, availabilityExplanation, quickCounter = false,
}: {
  t: Task;
  isPriority: boolean;
  visibleIds: number[];
  allowMove?: boolean;
  questCard?: boolean;
  canDefer?: boolean;
  allView?: boolean;
  availabilityMessage?: string;
  availabilityExplanation?: TaskAvailabilityExplanation;
  quickCounter?: boolean;
}) {
  const toggleTask = useStore((s) => s.toggleTask);
  const toggleExpanded = useStore((s) => s.toggleExpanded);
  const moveTask = useStore((s) => s.moveTask);
  const deferTask = useStore((s) => s.deferTask);
  const deleteTask = useStore((s) => s.deleteTask);
  const duplicateTask = useStore((s) => s.duplicateTask);
  const toSomeday = useStore((s) => s.toSomeday);
  const requestEditTask = useStore((s) => s.requestEditTask);
  const folders = useStore((s) => s.folders);
  const zones = useStore((s) => s.zones);

  function completeFromCheckbox(event: MouseEvent<HTMLButtonElement>) {
    if (t.done || isNeg) {
      toggleTask(t.id);
      return;
    }
    runTaskCompletionWithReward(event, () => toggleTask(t.id));
  }

  const isNeg = t.type === 'negative';
  const hasBody = t.type !== 'simple' || hasTaskCounter(t) || !!t.note || !!t.expanded;
  const folder = t.folderId ? folders.find((f) => f.id === t.folderId) : null;
  const rep = repeatLabel(t);
  const date = taskPlanDate(t);
  const showXp = useStore(s => s.preferences.showGamification !== false);
  const missingZone = t.zoneId != null && !zones.some(zone => zone.id === t.zoneId);
  const deferralStatus = questDeferralLabel(t, zones);
  const showDeferActions = canDefer && !t.done && !isNeg;

  return (
    <div
      className={'tc' + (t.done ? ' done-t' : '') + (isNeg ? ' neg-tc' : '') + (isPriority ? ' prio-task' : '')}
      style={{ borderLeftColor: t.zoneColor || 'transparent' }}
    >
      <div className="th">
        <button
          className={'ck' + (t.done && !isNeg ? ' on' : '') + (isNeg ? ' neg-ck' : '')}
          onClick={completeFromCheckbox}
          aria-label={(isNeg ? 'Зафіксувати: ' : t.done ? 'Позначити невиконаною: ' : 'Виконати: ') + t.title}
          aria-pressed={t.done}
          style={{ marginTop: 2, flexShrink: 0 }}
        >
          {!isNeg && t.done ? <Icon name="check" /> : null}
        </button>
        <div style={{ flex: 1, minWidth: 0 }}>
          <button className="tt task-title-button" onClick={() => requestEditTask(t.id)}>{t.title}</button>
          <div className="task-schedule-label">
            {t.someday ? 'Колись' : t.questDeferral && !t.done ? deferralStatus : date ? `${date < fmtDate(new Date()) && !t.done ? 'Прострочено · ' : ''}${new Date(date + 'T12:00:00').toLocaleDateString('uk', { day: 'numeric', month: 'short' })}${t.type === 'sched' && t.schedTime ? ' · ' + t.schedTime : ''}` : 'Без дати'}
            {t.snoozeUntil && t.snoozeUntil > Date.now() ? ' · відкладено до ' + new Date(t.snoozeUntil).toLocaleString('uk', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : ''}
            {t.type === 'timewin' ? ` · ${t.windowStart}–${t.windowEnd}` : ''}
          </div>
        </div>
        {hasBody && (
          <button className="ab ghost" aria-label={(t.expanded ? 'Згорнути: ' : 'Розгорнути: ') + t.title} aria-expanded={t.expanded} onClick={() => toggleExpanded(t.id)} style={{ flexShrink: 0, marginTop: 1 }}>
            <Icon name="expand" />
          </button>
        )}
      </div>
      <div className="tc-meta">
        {hasTaskCounter(t) && <span className="badge">{t.counter || 0}{taskCounterTarget(t) > 0 ? '/' + taskCounterTarget(t) : ''} {COUNTER_UNITS[t.counterUnit || 'count']}</span>}
        {t.zoneName && !missingZone && <span className="badge zbadge" style={{ borderLeftColor: t.zoneColor || undefined }}>{t.zoneName}</span>}
        {missingZone && allView && <span className="badge" style={{ background: 'rgba(226,75,74,.12)', color: 'var(--neg)', border: '1px solid rgba(226,75,74,.25)' }} title={'Зону «' + (t.zoneName || ('#' + t.zoneId)) + '» видалено; задачу залишено в усіх задачах'}>Зону видалено · {t.zoneName || ('#' + t.zoneId)}</span>}
        {allView && !missingZone && availabilityMessage && <span className="badge" title={availabilityMessage}>{availabilityMessage}</span>}
        {t.type === 'zonelinked' && !t.zoneId && (
          <span className="badge" style={{ background: 'rgba(226,75,74,.12)', color: 'var(--neg)', border: '1px solid rgba(226,75,74,.25)' }} title="Не вказано зону">! зона</span>
        )}
        {t.type === 'alarm' && t.alarmTime && <span className={'badge ab-b' + (t.alarmFired ? ' fired' : '')}>{t.alarmTime}</span>}
        {rep && <span className="badge rep-b">{rep}</span>}
        <span className="badge" style={{ background: 'var(--s2)' }}>{TASK_TYPE_LABELS[t.type] || t.type}</span>
        {!!(t.tags || []).length && <span className="badge" style={{ color: 'var(--z)' }}>#{t.tags[0]}</span>}
        {folder && <span className="badge" style={{ background: 'var(--s2)' }} title={folder.nm}>{folder.ico || <Icon name="folder" />}</span>}
        {isNeg && <span className="badge" style={{ background: 'rgba(226,75,74,.12)', color: 'var(--neg)' }}>−{t.negXp || 5} досвіду</span>}
        {showXp && (isNeg ? (
          <span style={{ fontSize: 10, color: 'var(--neg)', fontFamily: "'Space Mono',monospace", flexShrink: 0 }}>−{t.negXp || 5}</span>
        ) : (
          <span style={{ fontSize: 10, color: 'var(--xp)', fontFamily: "'Space Mono',monospace", flexShrink: 0 }}>+{TXP[t.type] ?? 10}</span>
        ))}
        <div className="tc-acts">
          {showDeferActions && <>
            <button className="ab quest-defer-btn" onClick={() => deferTask(t.id, 'next-zone', Date.now())} title="Не зараз: перенести в наступну часову зону" aria-label={'Не зараз: ' + t.title}>Не зараз</button>
            <button className="ab quest-defer-btn" onClick={() => deferTask(t.id, 'not-today', Date.now())} title="Не сьогодні: повернути пізніше" aria-label={'Не сьогодні: ' + t.title}>Не сьогодні</button>
          </>}
          {!questCard && <>
            {allowMove && <><button className="ab" onClick={() => moveTask(t.id, -1, visibleIds)} title="Вгору" aria-label={'Підняти: ' + t.title}>↑</button><button className="ab" onClick={() => moveTask(t.id, 1, visibleIds)} title="Вниз" aria-label={'Опустити: ' + t.title}>↓</button></>}
            <button className="ab eb" onClick={() => requestEditTask(t.id)} title="Редагувати" aria-label={'Редагувати: ' + t.title}><Icon name="edit" /></button>
            <button className="ab" onClick={() => duplicateTask(t.id)} title="Дублювати" aria-label={'Дублювати: ' + t.title}>⧉</button>
            <button className="ab" onClick={() => toSomeday(t.id)} title="На колись" aria-label={'На колись: ' + t.title}><Icon name="archive" /></button>
            <button className="ab del" onClick={() => deleteTask(t.id)} title="У кошик" aria-label={'У кошик: ' + t.title}><Icon name="trash" /></button>
          </>}
        </div>
      </div>
      {availabilityExplanation && <div className="task-availability-note" role="status" style={{ padding: '8px 10px', margin: '6px 0 0 28px', borderRadius: 8, background: 'var(--s1)', color: 'var(--t2)', fontSize: 12, lineHeight: 1.45 }}>
        <span>{availabilityExplanation.message}</span>
        {!!availabilityExplanation.actions?.length && <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 7 }}>
          {availabilityExplanation.actions.map(action => <button type="button" className="secondary-action" key={action.tab} onClick={() => useStore.getState().switchTab(action.tab)}>{action.label}</button>)}
        </div>}
      </div>}
      {quickCounter && hasTaskCounter(t) && !t.expanded && <div className="daily-counter-controls"><TaskCounterControls t={t} /></div>}
      {hasBody && (
        <div className={'tb-body' + (t.expanded ? ' open' : '')}>
          {t.expanded && <TaskBody t={t} />}
        </div>
      )}
    </div>
  );
}
