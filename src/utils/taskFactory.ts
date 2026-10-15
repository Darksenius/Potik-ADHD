import type { Task, TaskType, Zone } from '../types';

/** All task entry points use the same defaults, including normalized imports. */
export function createTaskRecord(id: number, title: string, type: TaskType,
  zoneId: number | null = null, zone?: Zone | null, alarmTime = '', created = new Date().toISOString()): Task {
  return {
    id, title, type, done: false, someday: false, zoneId,
    zoneColor: zone?.color || null, zoneName: zone?.nm || null, expanded: false, created,
    items: type === 'check' ? [] : undefined,
    counter: type === 'counter' || type === 'negative' ? 0 : undefined,
    counterTarget: type === 'counter' ? 10 : undefined,
    counterEnabled: type === 'counter', counterStep: 1, counterUnit: 'count',
    negXp: type === 'negative' ? 5 : undefined, note: type === 'note' ? '' : undefined,
    alarmTime: type === 'alarm' ? alarmTime : undefined, alarmFired: false,
    schedDate: type === 'sched' ? '' : undefined, schedTime: type === 'sched' ? '' : undefined,
    firedSched: type === 'sched' ? false : undefined, firedPre: type === 'sched' ? false : undefined,
    windowStart: type === 'timewin' ? '07:00' : undefined, windowEnd: type === 'timewin' ? '09:00' : undefined,
    completedToday: type === 'timewin' ? false : undefined, ctxTags: type === 'ctx' ? [] : undefined,
    zoneDoneToday: type === 'zonelinked' ? false : undefined,
    repeat: 'none', repeatDays: [false, false, false, false, false, false, false], tags: [],
  };
}
