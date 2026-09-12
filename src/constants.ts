import type { TaskType } from './types';

/** TL — рядок 1238 */
export const TASK_TYPE_LABELS: Record<TaskType, string> = {
  simple: 'Проста', check: 'Чекліст', counter: 'Лічильник', note: 'Нотатка', alarm: 'Будильник',
  sched: 'Заплановано', timewin: 'Вікно часу', pomodoro: 'Pomodoro', habit: 'Звичка', kid: 'Дитяча',
  ctx: 'Контекст', negative: 'Шкідлива', zonelinked: 'До зони',
};

/** REP — рядок 1242 */
export const REPEAT_LABELS: Record<string, string> = {
  none: 'Без повторення', daily: 'Щодня', weekly: 'Щотижня', weekdays: 'Пн–Пт',
  weekend: 'Вихідні', everyzone: 'Кожна зона', interval: 'Через X', custom: 'Свій',
};

/** CTAGS — рядок 1244 */
export const CONTEXT_TAGS = ['🏠 Вдома', '📱 Телефон', '💼 Робота', '🤫 Тихо', '👶 Дитина'];

/** NRJ — рядок 1246 (індекс 0 не використовується, енергія 1..5) */
export const ENERGY_LABELS = ['', 'Вичерпаний', 'Низька', 'Нормально', 'Добре', 'Заряджений 🔥'];

/** PLAN_TEMPLATES — рядки 3425-3430. Прості мітки графіка дня (не dayTemplates/зони!). */
export const PLAN_TEMPLATES = [
  { id: '9to18', label: '9–18', hours: '09:00–18:00' },
  { id: '10to19', label: '10–19', hours: '10:00–19:00' },
  { id: 'remote', label: 'Remote', hours: '09:00–17:00' },
  { id: 'half', label: 'Пів дня', hours: '09:00–13:00' },
];

/** WKC_SPANS — рядок 3719. Періоди застосування графіка тижня. */
export const WKC_SPANS: [number, string][] = [
  [14, '2 тижні'],
  [28, '4 тижні'],
  [91, '3 місяці'],
  [182, 'пів року'],
  [365, 'рік'],
];
