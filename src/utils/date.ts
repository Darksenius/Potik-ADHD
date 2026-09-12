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
