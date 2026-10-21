import type { Task } from '../types';

export const COUNTER_UNITS = { count: 'раз', ml: 'мл', min: 'хв', kcal: 'ккал' } as const;
export function hasTaskCounter(task: Task): boolean {
  return task.type !== 'negative' && (task.type === 'counter' || task.counterEnabled === true);
}
export function taskCounterStep(task: Task): number {
  return Number.isFinite(task.counterStep) && task.counterStep! > 0 ? task.counterStep! : 1;
}
export function taskCounterTarget(task: Task): number {
  return Number.isFinite(task.counterTarget) ? Math.max(0, task.counterTarget!) : task.type === 'counter' ? 10 : 0;
}
export function resetTaskProgress(task: Task): Partial<Task> {
  return {
    ...(hasTaskCounter(task) || task.type === 'negative' ? { counter: 0, cntHit: false, counterCreditedProgress: 0 } : {}),
    ...(task.items ? { items: task.items.map(item => ({ ...item, done: false, credited: false })) } : {}),
    counterDay: undefined,
  };
}
