import type { AppSlice } from '../store';
import type { AchievementCheckContext, Achievement, EnergyLevel, RareEvent } from '../../types';

/**
 * ══ XP ══ + ══ ENERGY ══  (перенесено з www/index.html, рядки 1546–1604)
 *
 * ЦЕ ПОВНІСТЮ ПЕРЕНЕСЕНА ЛОГІКА (не заглушка) — еталонний приклад того, як
 * переносити секцію: DOM-виклики (xpPop, updXP, лічильники в HTML) замінені
 * на похідний UI-стан (`xpPopup`, `levelUpFlash`), який React-компонент
 * читає через useEffect і сам ховає через таймер — стор більше не займається
 * setTimeout-ами для UI, лише зберігає ФАКТ «щойно сталась подія X».
 */

const XP_DAILY_CAP = 250;

function xpForLevel(level: number): number {
  return level * 100; // XPL()
}

export const ACHIEVEMENTS: Achievement[] = [
  { id: 'first', ico: '🎯', nm: 'Перший крок', check: (s) => s.done >= 1 },
  { id: 'ten', ico: '🔟', nm: '10 задач', check: (s) => s.done >= 10 },
  { id: 'xp100', ico: '🧘', nm: '100 досвіду', check: (s) => s.xpTotal >= 100 },
  { id: 'zones', ico: '🗂', nm: '5 зон', check: (s) => s.zonesCount >= 5 },
  { id: 'pro', ico: '🏆', nm: 'Рівень 5', check: (s) => s.level >= 5 },
  { id: 'habit', ico: '🔁', nm: 'Звичка', check: (s) => s.done >= 5 },
];

export interface GamificationSlice {
  xp: number;
  xpTotal: number;
  level: number;
  /** Кількість виконаних задач (було S.done — інкрементується з tasksSlice.toggleTask) */
  done: number;
  /** Кількість кліків по «шкідливих» задачах/звичках */
  negCount: number;
  unlocked: string[];
  energy: EnergyLevel;
  lastEnergyXp: number;
  dayXpGained: number;
  xpDay: string;
  /** TODO Крок «Щоденний скид»: логіка оновлення серії ще не перенесена (checkDailyReset, рядок 3037) */
  streak: number;

  /** Похідний UI-стан замість xpPop()-виклику DOM напряму (рядки 1562–1567) */
  xpPopup: { text: string; tone: 'gain' | 'loss' | 'muted' } | null;
  /** Похідний UI-стан замість #lvlup модалки (рядок 1582) — число рівня або null */
  levelUpFlash: number | null;

  /**
   * S.rareEvents — рендериться в renderRare() поруч із renderAchs() у вкладці
   * «Статистика» (секція РІДКІСНІ ПОДІЇ, рядок 2556), тому логічно тут, а не
   * в планувальнику. Форма елементів ще не звірена побітово — TODO.
   */
  rareEvents: RareEvent[];

  /** award() — рядки 1568–1584. Додає/віднімає XP з денним лімітом приросту +250. */
  award: (amount: number) => void;
  /** setNrj() — рядки 1590–1602, БЕЗ queueSave()/logDayData() (див. persistence-шар) */
  setEnergy: (level: EnergyLevel) => void;
  /** checkAchs() — рядок 1586. Читає zones.length із іншого slice через get(). */
  checkAchievements: () => void;
  incrementDoneCount: () => void;
  incrementNegCount: () => void;
  dismissXpPopup: () => void;
  dismissLevelUp: () => void;
  /** addRareEvent(nm,dir) — рядки 2558-2564, без prompt()/confirm() (питає компонент) */
  addRareEvent: (name: string, dir: 'up' | 'down') => void;
  /** chgRare(id,d) — рядок 2565 */
  changeRareEvent: (id: number, delta: number) => void;
  /** delRare(id) — рядок 2572 */
  deleteRareEvent: (id: number) => void;
}

export const createGamificationSlice: AppSlice<GamificationSlice> = (set, get) => ({
  xp: 0,
  xpTotal: 0,
  level: 1,
  done: 0,
  negCount: 0,
  unlocked: [],
  energy: 0,
  lastEnergyXp: 0,
  dayXpGained: 0,
  xpDay: '',
  streak: 1,

  xpPopup: null,
  levelUpFlash: null,
  rareEvents: [],

  award: (amountIn) => {
    let amount = amountIn;
    const today = new Date().toDateString();
    // ensureXpDay() — рядки 1558–1561
    if (get().xpDay !== today) {
      set({ xpDay: today, dayXpGained: 0 });
    }

    // Денний ліміт ПРИРОСТУ: максимум +250 чистими за добу. Мінуси завжди
    // застосовуються і зменшують dayXpGained — «відкриваючи» місце назад.
    if (amount > 0) {
      const room = Math.max(0, XP_DAILY_CAP - get().dayXpGained);
      if (amount > room) amount = room;
      if (amount === 0) {
        set({ xpPopup: { text: 'Ліміт 250 досвіду/добу', tone: 'muted' } });
        return;
      }
    }

    set((s) => {
      const dayXpGained = s.dayXpGained + amount;
      let xp = s.xp + amount;
      const xpTotal = amount > 0 ? s.xpTotal + amount : s.xpTotal;
      if (xp < 0) xp = 0;

      let level = s.level;
      let levelUpFlash: number | null = null;
      if (amount > 0 && xp >= xpForLevel(level)) {
        xp -= xpForLevel(level);
        level += 1;
        levelUpFlash = level;
      }

      return {
        dayXpGained,
        xp,
        xpTotal,
        level,
        xpPopup: {
          text: (amount > 0 ? '+' : '') + amount + ' досвіду',
          tone: amount < 0 ? 'loss' : 'gain',
        },
        ...(levelUpFlash ? { levelUpFlash } : {}),
      };
    });

    get().checkAchievements();
  },

  setEnergy: (level) => {
    set({ energy: level });
    const now = Date.now();
    const last = get().lastEnergyXp;
    // XP лише раз на годину — страховка від помилкових повторних кліків
    if (!last || now - last >= 3_600_000) {
      set({ lastEnergyXp: now });
      get().award(3);
    }
  },

  checkAchievements: () => {
    const s = get();
    const ctx: AchievementCheckContext = {
      done: s.done,
      xpTotal: s.xpTotal,
      level: s.level,
      zonesCount: s.zones.length,
    };
    const newlyUnlocked = ACHIEVEMENTS.filter((a) => !s.unlocked.includes(a.id) && a.check(ctx)).map(
      (a) => a.id
    );
    if (newlyUnlocked.length) {
      set({ unlocked: [...s.unlocked, ...newlyUnlocked] });
    }
  },

  incrementDoneCount: () => set((s) => ({ done: s.done + 1 })),
  incrementNegCount: () => set((s) => ({ negCount: s.negCount + 1 })),
  dismissXpPopup: () => set({ xpPopup: null }),
  dismissLevelUp: () => set({ levelUpFlash: null }),

  addRareEvent: (name, dir) => {
    const nm = name.trim();
    if (!nm) return;
    const event: RareEvent = { id: Date.now(), nm, val: 0, dir, unit: '' };
    set((s) => ({ rareEvents: [...s.rareEvents, event] }));
  },

  changeRareEvent: (id, delta) => {
    set((s) => ({
      rareEvents: s.rareEvents.map((r) => (r.id === id ? { ...r, val: Math.max(0, r.val + delta) } : r)),
    }));
  },

  deleteRareEvent: (id) => {
    set((s) => ({ rareEvents: s.rareEvents.filter((r) => r.id !== id) }));
  },
});
