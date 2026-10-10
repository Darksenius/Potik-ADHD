/** Перенесено 1:1 з www/index.html, рядок 3320. Формат ключа дня всюди в застосунку. */
export function fmtDate(d: Date): string {
  return (
    d.getFullYear() +
    '-' +
    String(d.getMonth() + 1).padStart(2, '0') +
    '-' +
    String(d.getDate()).padStart(2, '0')
  );
}

/** Перенесено з www/index.html, рядок 1305 (nowHM). */
export function nowHM(): { h: number; m: number } {
  const n = new Date();
  return { h: n.getHours(), m: n.getMinutes() };
}

/** Перенесено з www/index.html, рядок 1306 (ts). */
export function hmToString(hm: { h: number; m: number }): string {
  return String(hm.h).padStart(2, '0') + ':' + String(hm.m).padStart(2, '0');
}

/** Перенесено з www/index.html, рядок 1307 (toMin). */
export function toMinutes(time: string): number {
  const [h, m] = time.split(':').map(Number);
  return h * 60 + m;
}

/** Accept legacy 9:00 values, but never let an invalid time become a reminder. */
export function normalizeTime(value: string | null | undefined): string | undefined {
  const match = /^(\d{1,2}):(\d{2})$/.exec(value || '');
  if (!match || Number(match[1]) > 23 || Number(match[2]) > 59) return undefined;
  return match[1].padStart(2, '0') + ':' + match[2];
}

export function isDateKey(value: string | null | undefined): value is string {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(value + 'T12:00:00');
  return Number.isFinite(date.getTime()) && fmtDate(date) === value;
}

export function localDateTimeMs(day: string | undefined, time: string | undefined): number | undefined {
  const normalized = normalizeTime(time);
  if (!isDateKey(day) || !normalized) return undefined;
  const date = new Date(day + 'T' + normalized + ':00');
  return Number.isFinite(date.getTime()) ? date.getTime() : undefined;
}

/** Перенесено з www/index.html, рядок 1243. Пн..Нд. */
export const WD = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Нд'];

/** Перенесено з www/index.html, рядок 3321 (fmtHuman). */
const WD_HUMAN = ['Нд', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'];
const MONTHS_HUMAN = ['Січ', 'Лют', 'Бер', 'Кві', 'Тра', 'Чер', 'Лип', 'Сер', 'Вер', 'Жов', 'Лис', 'Гру'];
export function fmtHuman(d: Date): string {
  return WD_HUMAN[d.getDay()] + ', ' + d.getDate() + ' ' + MONTHS_HUMAN[d.getMonth()];
}

/** Перенесено з www/index.html, рядок 3328 (addDaysDs). */
export function addDaysDs(ds: string, k: number): string {
  const d = new Date(ds + 'T12:00:00');
  d.setDate(d.getDate() + k);
  return fmtDate(d);
}

/** Перенесено з www/index.html, рядок 3329 (daysBetweenDs). */
export function daysBetweenDs(a: string, b: string): number {
  return Math.round((new Date(b + 'T12:00:00').getTime() - new Date(a + 'T12:00:00').getTime()) / 864e5);
}
