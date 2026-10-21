import type { AppSlice } from '../store';
import type { Task } from '../../types';
import { fmtDate } from '../../utils/date';
import { resetTaskProgress } from '../../utils/taskCounter';
import { repeatDueOn, taskPlanDate } from '../../utils/taskSchedule';
import { seedKnownCompletions } from '../../utils/taskHistory';

export { repeatDueOn } from '../../utils/taskSchedule';

/** A day boundary and a reminder tick must apply the same occurrence reset. */
let lastResetDate = new Date().toDateString();

function resetOccurrence(task: Task, day: string): Task {
  return {
    ...task,
    ...resetTaskProgress(task),
    done: false,
    completionCredited: false,
    completionCreditDate: undefined,
    doneDate: undefined,
    doneAt: undefined,
    occurrenceId: undefined,
    alarmFired: false,
    firedSched: false,
    firedPre: false,
    nextRepeatAt: 0,
    questDeferral: undefined,
    completionDeferral: undefined,
    counterDay: day,
    ...(task.type === 'timewin' ? { completedToday: false } : {}),
    ...(task.type === 'zonelinked' ? { zoneDoneToday: false } : {}),
    ...(task.type === 'sched' ? { schedDate: day, planDate: day } :
      taskPlanDate(task) ? { planDate: day } : {}),
  };
}

function progressDay(task: Task): string {
  // Undo retains its completion credit date. It must not reset today's
  // occurrence merely because its earlier counter progress was yesterday.
  return [task.counterDay, task.completionCreditDate, task.doneDate,
    taskPlanDate(task), fmtDate(new Date(task.created))]
    .filter((day): day is string => !!day).sort().pop() || '';
}

export interface LifecycleSlice {
  resetRepeatingTasksFor: (date: Date) => void;
  checkZoneRepeats: (date: Date, taskId?: number) => void;
  dailyResetIfNeeded: (savedDate: string | null) => boolean;
  checkDailyReset: () => void;
}

export const createLifecycleSlice: AppSlice<LifecycleSlice> = (set, get) => ({
  resetRepeatingTasksFor: (date) => {
    const day = fmtDate(date);
    set((state) => {
      let changed = false;
      const tasks = state.tasks.map((task) => {
        if (task.trashed || task.someday || (task.questDeferral && !task.done) || !task.repeat || task.repeat === 'none' || task.repeat === 'everyzone') return task;
        const anchor = taskPlanDate(task);
        if ((anchor && anchor > day) || (task.snoozeUntil && task.snoozeUntil > date.getTime())) return task;
        if (!repeatDueOn(task, date)) return task;
        if (task.repeat === 'interval') {
          if (!task.done) return task;
        } else if (progressDay(task) >= day) {
          return task;
        }
        // Calendar occurrences also reset unfinished counter/checklist
        // progress, but only on an eligible day, never on a weekend/off day.
        changed = true;
        return resetOccurrence(task, day);
      });
      return changed ? { tasks, taskHistory: seedKnownCompletions(state.taskHistory, state.tasks) } : state;
    });
  },

  checkZoneRepeats: (date, taskId) => {
    const day = fmtDate(date);
    set((state) => {
      let changed = false;
      const tasks = state.tasks.map((task) => {
        if (taskId !== undefined && task.id !== taskId) return task;
        if (task.trashed || task.someday || (task.questDeferral && !task.done) || task.repeat !== 'everyzone' || !task.zoneId) return task;
        const anchor = taskPlanDate(task);
        if ((anchor && anchor > day) || (task.snoozeUntil && task.snoozeUntil > date.getTime())) return task;
        const key = get().getZoneOccurrenceKey(task.zoneId, date);
        if (!key || key === task.zoneOccurrenceKey) return task;
        changed = true;
        // First observation identifies the current occurrence. Reopening or
        // manually toggling a zone within the same slot cannot farm XP.
        if (!task.zoneOccurrenceKey) return { ...task, zoneOccurrenceKey: key };
        return { ...resetOccurrence(task, day), zoneOccurrenceKey: key };
      });
      return changed ? { tasks, taskHistory: seedKnownCompletions(state.taskHistory, state.tasks) } : state;
    });
  },

  dailyResetIfNeeded: (savedDate) => {
    const now = new Date();
    const today = now.toDateString();
    lastResetDate = today;
    if (!savedDate || savedDate === today) return false;
    const day = fmtDate(now);
    set((state) => ({
      recur: state.recur.map((routine) => ({ ...routine, val: 0, done: false })),
      tasks: state.tasks.map((task) => {
        if (task.trashed) return task;
        // Preset negative tasks are independent date-scoped journals; midnight
        // must not erase the progress stored on an earlier planned date.
        if (task.type === 'negative' && task.dayPresetKey && task.repeat === 'none'
          && task.planDate && task.planDate < day) return task;
        // A negative task is a daily journal, not a completable occurrence.
        // A click already recorded today must survive a delayed reset.
        if (task.type === 'negative' && (!task.counterDay || task.counterDay < day)) {
          return { ...task, counter: 0, counterCreditedProgress: 0, counterDay: day };
        }
        // Alarms and time windows reset only through their chosen repeat.
        return task;
      }),
    }));
    get().resetRepeatingTasksFor(now);
    return true;
  },

  checkDailyReset: () => {
    get().dailyResetIfNeeded(lastResetDate);
  },
});
