import type { AppSlice } from '../store';
import type { AchievementCheckContext, Achievement, EnergyLevel, RareEvent } from '../../types';
import { PROGRESSION_VERSION, initializeProgression as migrateProgression, recordProgressDay as addProgressDay, progressionFromXp, totalXpToReachLevel } from '../../utils/progression';

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
const progress = (current: (s: AchievementCheckContext) => number, target: number) =>
  (s: AchievementCheckContext) => ({ current: Math.min(target, current(s)), target });
const achievement = (id: string, ico: string, nm: string, description: string,
  current: (s: AchievementCheckContext) => number, target: number): Achievement => ({
  id, ico, nm, description, progress: progress(current, target), check: (s) => current(s) >= target,
});
const done = (s: AchievementCheckContext) => s.done;
const xp = (s: AchievementCheckContext) => s.xpTotal;
const level = (s: AchievementCheckContext) => s.level;
const days = (s: AchievementCheckContext) => s.activeDays;

export const ACHIEVEMENTS: Achievement[] = [
  achievement('first', '🎯', 'Перший крок', 'Заверши першу задачу.', done, 1),
  achievement('ten', '🔟', '10 задач', 'Заверши десять задач.', done, 10),
  achievement('xp100', '🧘', '100 досвіду', 'Зароби загалом 100 XP.', xp, 100),
  achievement('zones', '🗂', '5 зон', 'У твоєму просторі є п’ять зон.', (s) => s.zonesCount, 5),
  achievement('pro', '🏆', 'Рівень 5', 'Дійди до п’ятого рівня.', level, 5),
  achievement('habit', '🔁', 'П’ять завершень', 'Заверши п’ять задач.', done, 5),
  achievement('done25', '📌', 'Упевнена хода', 'Заверши 25 задач.', done, 25),
  achievement('done50', '🧭', 'Пів сотні кроків', 'Заверши 50 задач.', done, 50),
  achievement('done100', '💯', 'Сотня справ', 'Заверши 100 задач.', done, 100),
  achievement('done250', '🌟', 'Чверть тисячі', 'Заверши 250 задач.', done, 250),
  achievement('done500', '🏅', 'П’ятсот перемог', 'Заверши 500 задач.', done, 500),
  achievement('xp500', '✨', 'Зоряний запас', 'Зароби загалом 500 XP.', xp, 500),
  achievement('xp1000', '🌠', 'Тисяча XP', 'Зароби загалом 1 000 XP.', xp, 1000),
  achievement('xp2500', '🔥', 'Велике полум’я', 'Зароби загалом 2 500 XP.', xp, 2500),
  achievement('xp5000', '💎', 'Скарб досвіду', 'Зароби загалом 5 000 XP.', xp, 5000),
  achievement('level10', '🛠', 'Будівничий звичок', 'Дійди до рівня 10.', level, 10),
  achievement('level15', '🎼', 'Майстер ритму', 'Дійди до рівня 15.', level, 15),
  achievement('level20', '🪨', 'Витривалий', 'Дійди до рівня 20.', level, 20),
  achievement('level25', '♟️', 'Стратег', 'Дійди до рівня 25.', level, 25),
  achievement('level30', '💡', 'Натхненник', 'Дійди до рівня 30.', level, 30),
  achievement('level40', '🧑‍🚀', 'Провідник', 'Дійди до рівня 40.', level, 40),
  achievement('level50', '🎻', 'Віртуоз', 'Дійди до рівня 50.', level, 50),
  achievement('level60', '🤝', 'Опора команди', 'Дійди до рівня 60.', level, 60),
  achievement('level75', '🗻', 'Майстер шляху', 'Дійди до рівня 75.', level, 75),
  achievement('level100', '👑', 'Легенда Потоку', 'Дійди до рівня 100.', level, 100),
  achievement('active1', '🌱', 'Початок шляху', 'Зроби щось корисне в один день.', days, 1),
  achievement('active3', '🍀', 'Три активні дні', 'Збери три дні з реальною активністю.', days, 3),
  achievement('active7', '🗓️', 'Тиждень руху', 'Збери сім активних днів.', days, 7),
  achievement('active14', '🌿', 'Два тижні', 'Збери 14 активних днів.', days, 14),
  achievement('active21', '🌳', 'Три тижні', 'Збери 21 активний день.', days, 21),
  achievement('active30', '🦋', 'Місяць поступу', 'Збери 30 активних днів.', days, 30),
  achievement('active45', '🧶', 'Стійкий темп', 'Збери 45 активних днів.', days, 45),
  achievement('active60', '🪴', 'Два місяці дій', 'Збери 60 активних днів.', days, 60),
  achievement('active75', '🦉', 'Мудрий поступ', 'Збери 75 активних днів.', days, 75),
  achievement('active100', '🎉', 'Сто активних днів', 'Збери 100 днів, у які ти справді щось зробив.', days, 100),
];

export interface GamificationSlice {
  xp: number;
  xpTotal: number;
  level: number;
  progressionVersion: number;
  progressDays: string[];
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
  award: (amount: number) => void;
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
        set((s) => ({
          progressDays: addProgressDay(s.progressDays),
          xpPopup: { text: 'Ліміт 250 досвіду/добу', tone: 'muted' },
        }));
        get().checkAchievements();
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
        ...(amount > 0 ? { progressDays: addProgressDay(s.progressDays) } : {}),
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
    set(migration);
    get().checkAchievements();
  },
  recordProgressDay: (at) => {
    if (get().progressionVersion !== PROGRESSION_VERSION) set(migrateProgression(get()));
    set((s) => {
      const progressDays = addProgressDay(s.progressDays, at);
      return progressDays === s.progressDays ? s : { progressDays };
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
