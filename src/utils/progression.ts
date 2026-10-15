import type { QuickNote, TaskHistoryEvent } from '../types';

export const PROGRESSION_VERSION = 2;
const MAX_LEVEL = Number.MAX_SAFE_INTEGER;

/** XP required to move from `level` to the next level in progression v2. */
export function xpForLevel(level: number): number {
  const safeLevel = Math.max(1, Math.min(MAX_LEVEL, Math.floor(Number.isFinite(level) ? level : 1)));
  return 100 + 5 * (safeLevel - 1);
}

/** Lifetime XP threshold at the start of `level` in progression v2. */
export function totalXpToReachLevel(level: number): number {
  const k = Math.max(0, Math.min(MAX_LEVEL - 1, Math.floor(Number.isFinite(level) ? level : 1) - 1));
  return 100 * k + (5 * k * (k - 1)) / 2;
}

export interface ProgressionPosition {
  level: number;
  xp: number;
  xpNeeded: number;
}

/** Resolve lifetime XP with a bounded binary search, so imported totals never create a long level loop. */
export function progressionFromXp(value: number): ProgressionPosition {
  const xpTotal = Number.isFinite(value) ? Math.max(0, value) : 0;
  let low = 1;
  let high = MAX_LEVEL;
  while (low < high) {
    const mid = low + Math.ceil((high - low) / 2);
    if (totalXpToReachLevel(mid) <= xpTotal) low = mid;
    else high = mid - 1;
  }
  const xpNeeded = xpForLevel(low);
  return { level: low, xp: Math.max(0, xpTotal - totalXpToReachLevel(low)), xpNeeded };
}

export interface ProgressionSnapshot {
  progressionVersion?: number;
  progressDays?: string[];
  xp: number;
  xpTotal: number;
  level: number;
  taskHistory?: TaskHistoryEvent[];
  qnotes?: QuickNote[];
}

export interface ProgressionMigration {
  progressionVersion: number;
  progressDays: string[];
  xp: number;
  xpTotal: number;
  level: number;
}

function validLocalDay(day: string, today: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day) || day > today) return false;
  const [year, month, date] = day.split('-').map(Number);
  const parsed = new Date(year, month - 1, date);
  return parsed.getFullYear() === year && parsed.getMonth() === month - 1 && parsed.getDate() === date;
}

function localToday(at: number): string {
  const date = new Date(at);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

/** Add a real local calendar date once; invalid and future dates are ignored. */
export function recordProgressDay(days: readonly string[] = [], at = Date.now()): string[] {
  if (!Number.isFinite(at)) return [...days];
  const today = localToday(Date.now());
  const date = localToday(at);
  if (!validLocalDay(date, today)) return [...days];
  const unique = new Set(days.filter((item) => validLocalDay(item, today)));
  const alreadyRecorded = unique.has(date);
  unique.add(date);
  const sorted = [...unique].sort();
  if (alreadyRecorded && sorted.length === days.length && sorted.every((item, index) => item === days[index])) {
    return days as string[];
  }
  return sorted;
}

/** Migrate old cumulative level balance once and seed only dates with recorded activity. */
export function initializeProgression(state: ProgressionSnapshot, now = Date.now()): ProgressionMigration {
  const today = localToday(now);
  const seeded = new Set<string>();
  for (const day of state.progressDays || []) if (validLocalDay(day, today)) seeded.add(day);
  for (const event of state.taskHistory || []) {
    if (event.kind === 'completed' && validLocalDay(event.date, today)) seeded.add(event.date);
  }
  for (const note of state.qnotes || []) if (validLocalDay(note.date, today)) seeded.add(note.date);

  if ((state.progressionVersion || 0) >= PROGRESSION_VERSION) {
    const level = Math.max(1, Math.min(MAX_LEVEL, Math.floor(Number.isFinite(state.level) ? state.level : 1)));
    const xp = Math.max(0, Number.isFinite(state.xp) ? state.xp : 0);
    return { progressionVersion: PROGRESSION_VERSION, progressDays: [...seeded].sort(), xp,
      xpTotal: Math.max(0, Number.isFinite(state.xpTotal) ? state.xpTotal : 0), level };
  }

  const oldLevel = Math.max(1, Math.min(MAX_LEVEL, Math.floor(Number.isFinite(state.level) ? state.level : 1)));
  const oldXp = Math.max(0, Number.isFinite(state.xp) ? state.xp : 0);
  const oldBalance = (100 * (oldLevel - 1) * oldLevel) / 2 + oldXp;
  const mapped = progressionFromXp(oldBalance);
  return {
    progressionVersion: PROGRESSION_VERSION,
    progressDays: [...seeded].sort(),
    xp: mapped.xp,
    xpTotal: Math.max(0, Number.isFinite(state.xpTotal) ? state.xpTotal : 0),
    level: Math.max(oldLevel, mapped.level),
  };
}

export const LEVEL_TITLES: ReadonlyArray<{ level: number; title: string }> = [
  { level: 1, title: 'Початківець' },
  { level: 5, title: 'Дослідник' },
  { level: 10, title: 'Будівничий звичок' },
  { level: 15, title: 'Майстер ритму' },
  { level: 20, title: 'Витривалий' },
  { level: 25, title: 'Стратег' },
  { level: 30, title: 'Натхненник' },
  { level: 40, title: 'Провідник' },
  { level: 50, title: 'Віртуоз' },
  { level: 60, title: 'Опора команди' },
  { level: 75, title: 'Майстер шляху' },
  { level: 100, title: 'Легенда Потоку' },
];

export interface CollectibleToken {
  id: string;
  day: number;
  title: string;
  icon: string;
}

/** One non-XP collectible is earned at each five-active-day milestone. */
export const COLLECTIBLE_TOKENS: ReadonlyArray<CollectibleToken> = [
  { id: 'token-01', day: 5, title: 'Зернятко', icon: '🌰' },
  { id: 'token-02', day: 10, title: 'Листочок', icon: '🍃' },
  { id: 'token-03', day: 15, title: 'Краплина', icon: '💧' },
  { id: 'token-04', day: 20, title: 'Іскра', icon: '✨' },
  { id: 'token-05', day: 25, title: 'Пір’їнка', icon: '🪶' },
  { id: 'token-06', day: 30, title: 'Мушля', icon: '🐚' },
  { id: 'token-07', day: 35, title: 'Медальйон', icon: '🪙' },
  { id: 'token-08', day: 40, title: 'Зірочка', icon: '⭐' },
  { id: 'token-09', day: 45, title: 'Кристал', icon: '🔮' },
  { id: 'token-10', day: 50, title: 'Компас', icon: '🧭' },
  { id: 'token-11', day: 55, title: 'Мініатюрна книга', icon: '📕' },
  { id: 'token-12', day: 60, title: 'Місяць', icon: '🌙' },
  { id: 'token-13', day: 65, title: 'Сонячний знак', icon: '☀️' },
  { id: 'token-14', day: 70, title: 'Квітка', icon: '🌼' },
  { id: 'token-15', day: 75, title: 'Маяк', icon: '🗼' },
  { id: 'token-16', day: 80, title: 'Планета', icon: '🪐' },
  { id: 'token-17', day: 85, title: 'Паперовий кораблик', icon: '⛵' },
  { id: 'token-18', day: 90, title: 'Полярна зірка', icon: '🌟' },
  { id: 'token-19', day: 95, title: 'Корона', icon: '👑' },
  { id: 'token-20', day: 100, title: 'Знак легенди', icon: '🏵️' },
];

export function tokensEarnedForActiveDays(activeDays: number): CollectibleToken[] {
  const count = Number.isFinite(activeDays) ? Math.max(0, Math.floor(activeDays)) : 0;
  return COLLECTIBLE_TOKENS.filter((token) => count >= token.day);
}

/** Higher levels keep the last earned title. */
export function titleForLevel(level: number): string {
  let title = LEVEL_TITLES[0].title;
  for (const rank of LEVEL_TITLES) {
    if (level < rank.level) break;
    title = rank.title;
  }
  return title;
}
