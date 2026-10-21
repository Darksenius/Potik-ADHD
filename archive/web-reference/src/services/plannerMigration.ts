import type { PlanItem, Task } from '../types';
import { normalizeTime } from '../utils/date';
import { createTaskRecord } from '../utils/taskFactory';

/** Normalize the former second task model before a single atomic store update. */
export function normalizePlannerTasks(tasks: readonly Task[], nextId: number, items: Record<string, PlanItem[]>) {
  const result = tasks.slice();
  let nid = Math.max(nextId, 1, ...tasks.map(task => task.id + 1));
  const planItems: Record<string, PlanItem[]> = {};
  for (const [day, entries] of Object.entries(items)) {
    planItems[day] = [];
    for (const item of entries) {
      if (item.isEvent || item.isPrompt || item.title.includes('Заплануй наступний тиждень')) {
        planItems[day].push(item.isEvent ? { ...item } : { ...item, isPrompt: true, done: false });
        continue;
      }
      const time = normalizeTime(item.time);
      const created = new Date(day + 'T00:00:00').toISOString();
      const task = createTaskRecord(nid++, item.title, time ? 'sched' : 'simple', null, null, '', created);
      result.push({ ...task, planDate: day, ...(time ? { schedDate: day, schedTime: time, reminderEnabled: true } : {}),
        done: item.done, ...(item.done ? { doneDate: day, completionCredited: true, completionCreditDate: day } : {}) });
    }
  }
  return { tasks: result, nid, planItems };
}
