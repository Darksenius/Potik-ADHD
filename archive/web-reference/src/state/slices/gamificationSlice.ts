import progressionCatalog from '../../content/progression.json' with { type: 'json' };
import type { AppSlice } from '../store';
import type { AchievementCheckContext, Achievement, EnergyLevel, RareEvent } from '../../types';
import { PROGRESSION_VERSION, initializeProgression as migrateProgression, recordProgressDay as addProgressDay, progressionFromXp, totalXpToReachLevel } from '../../utils/progression';
import { archiveInsightClaims, claimDailyInsight, type DailyInsightClaim } from '../../utils/dailyInsights';

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
export const ACHIEVEMENTS: Achievement[] = progressionCatalog.achievements.map((definition) => {
  const current = (state: AchievementCheckContext) => state[definition.metric as keyof AchievementCheckContext];
  return { id: definition.id, ico: definition.icon, nm: definition.name, description: definition.description,
    progress: (state) => ({ current: Math.min(definition.target, current(state)), target: definition.target }),
    check: (state) => current(state) >= definition.target };
});

export interface GamificationSlice {
  xp: number;
  xpTotal: number;
  level: number;
  progressionVersion: number;
  progressDays: string[];
  dailyInsightClaims: DailyInsightClaim[];
  dailyInsightNotice: string | null;
  selectedInsightDay: string | null;
  dismissDailyInsightNotice: () => void;
  openDailyInsight: (day: string) => void;
  closeDailyInsight: () => void;
  /** Кількість виконаних задач (було S.done — інкрементується з tasksSlice.toggleTask) */
  done: number;
  /** Кількість кліків по «шкідливих» задачах/звичках */
  negCount: number;
  unlocked: string[];
  energy: EnergyLevel;
  lastEnergyXp: number;
  dayXpGained: number;
  xpDay: string;
  /** Historical persisted counter; current task history does not infer a streak from it. */
  streak: number;

  /** Похідний UI-стан замість xpPop()-виклику DOM напряму (рядки 1562–1567) */
  xpPopup: { text: string; tone: 'gain' | 'loss' | 'muted' } | null;
  /** Похідний UI-стан замість #lvlup модалки (рядок 1582) — число рівня або null */
  levelUpFlash: number | null;

  /**
   * S.rareEvents — рендериться в renderRare() поруч із renderAchs() у вкладці
   * «Статистика» (секція РІДКІСНІ ПОДІЇ, рядок 2556), тому логічно тут, а не
   * в планувальнику. Форму елементів перевіряє backupValidation.
   */
  rareEvents: RareEvent[];

  /** award() — рядки 1568–1584. Додає/віднімає XP з денним лімітом приросту +250. */
  award: (amount: number, activityAt?: number) => void;
  /** setNrj() — рядки 1590–1602, БЕЗ queueSave()/logDayData() (див. persistence-шар) */
  setEnergy: (level: EnergyLevel) => void;
  /** checkAchs() — рядок 1586. Читає zones.length із іншого slice через get(). */
  checkAchievements: () => void;
  incrementDoneCount: () => void;
  incrementNegCount: () => void;
  initializeProgression: () => void;
  recordProgressDay: (at?: number) => void;
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
  progressionVersion: 0,
  progressDays: [],
  dailyInsightClaims: [],
  dailyInsightNotice: null,
  selectedInsightDay: null,
  dismissDailyInsightNotice: () => set({ dailyInsightNotice: null }),
  openDailyInsight: (day) => {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) return;
    set({ selectedInsightDay: day, dailyInsightNotice: null });
  },
  closeDailyInsight: () => set({ selectedInsightDay: null }),
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

  award: (amountIn, activityAt) => {
    if (!Number.isFinite(amountIn) || amountIn === 0) return;
    if (get().progressionVersion !== PROGRESSION_VERSION) set(migrateProgression(get()));
    let amount = amountIn;
    const today = new Date().toDateString();
    // ensureXpDay() — рядки 1558–1561
    if (get().xpDay !== today) {
      set({ xpDay: today, dayXpGained: 0 });
    }

    // Денний ліміт позитивних нагород: віднімання XP не звільняє місце для
    // додаткових нагород у цей день.
    if (amount > 0) {
      const priorGain = get().dayXpGained;
      const safePriorGain = Number.isFinite(priorGain) ? Math.max(0, priorGain) : XP_DAILY_CAP;
      const room = Math.max(0, XP_DAILY_CAP - safePriorGain);
      if (amount > room) amount = room;
      if (amount === 0) {
        set({ xpPopup: { text: 'Ліміт 250 од. на добу', tone: 'muted' } });
        get().recordProgressDay(activityAt);
        return;
      }
    }

    set((s) => {
      const priorGain = Number.isFinite(s.dayXpGained) ? Math.max(0, s.dayXpGained) : XP_DAILY_CAP;
      const dayXpGained = priorGain + Math.max(0, amount);
      let xp = s.xp + amount;
      const xpTotal = amount > 0 ? s.xpTotal + amount : s.xpTotal;
      if (xp < 0) xp = 0;

      let level = s.level;
      let levelUpFlash: number | null = null;
      if (amount > 0) {
        const mapped = progressionFromXp(totalXpToReachLevel(level) + xp);
        if (mapped.level >= level) {
          xp = mapped.xp;
          level = mapped.level;
          if (level > s.level) levelUpFlash = level;
        }
      }

      return {
        dayXpGained,
        xp,
        xpTotal,
        level,
        xpPopup: {
          text: (amount > 0 ? '+' : '') + amount + '⚡',
          tone: amount < 0 ? 'loss' : 'gain',
        },
        ...(levelUpFlash ? { levelUpFlash } : {}),
      };
    });

    if (amount > 0) get().recordProgressDay(activityAt);
    else get().checkAchievements();
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
      activeDays: s.progressDays.length,
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
  initializeProgression: () => {
    const migration = migrateProgression(get());
    set({ ...migration, dailyInsightClaims: archiveInsightClaims(migration.progressDays, get().dailyInsightClaims) });
    get().checkAchievements();
  },
  recordProgressDay: (at) => {
    if (get().progressionVersion !== PROGRESSION_VERSION) set(migrateProgression(get()));
    set((s) => {
      const progressDays = addProgressDay(s.progressDays, at);
      const reward = claimDailyInsight(progressDays, s.dailyInsightClaims, at);
      return progressDays === s.progressDays && reward.claims === s.dailyInsightClaims ? s : {
        progressDays, dailyInsightClaims: reward.claims,
        ...(reward.earned ? { dailyInsightNotice: reward.earned.day } : {}),
      };
    });
    get().checkAchievements();
  },
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
