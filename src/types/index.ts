/**
 * Типи домену «Потік».
 *
 * Джерело істини — не абстрактна модель, а РЕАЛЬНІ форми даних з оригінального
 * www/index.html (mkTask(), mkNote(), collectState()/applyState(), planItems/…).
 * Кожен тип нижче містить посилання на рядки оригіналу, з яких він виведений,
 * щоб на наступних кроках рефакторингу можна було звірити 1:1.
 */

// ─────────────────────────────────────────────────────────────────────────
// ЗОНИ  (оригінал: рядки 1185–1191, 1309–1517)
// ─────────────────────────────────────────────────────────────────────────

export interface ZoneSlot {
  /** Час початку "HH:MM" */
  s: string;
  /** Час кінця "HH:MM" (може дорівнювати "24:00") */
  e: string;
}

export interface Zone {
  id: number;
  nm: string;
  color: string;
  slots: ZoneSlot[];
  desc?: string;
  /** Пріоритет при перекритті зон (вища — виграє) */
  prio?: number;
  /** Вимкнена користувачем (тумблер), лишається у списку, але неактивна */
  active?: boolean;
  /** Ручний тумблер вимкнення (z.off), ВІД якого й обчислюється active */
  off?: boolean;
  /** Прив'язана до плану тижня/дня (а не безстрокова) — див. КОНСТРУКТОР ТИЖНЯ */
  bound?: boolean;
  /** «Діяти щодня попри прив'язку» — пін для згаслої прив'язаної зони */
  actPin?: boolean;
}

// ─────────────────────────────────────────────────────────────────────────
// ЗАДАЧІ  (оригінал: mkTask() рядки 1827–1845, TL/TXP рядки 1238–1239)
// ─────────────────────────────────────────────────────────────────────────

export type TaskType =
  | 'simple'      // Проста
  | 'check'       // Чекліст
  | 'counter'     // Лічильник
  | 'note'        // Нотатка (задача з довільним текстом)
  | 'alarm'       // Будильник (щоденний час)
  | 'sched'       // Заплановано (конкретні дата+час)
  | 'timewin'     // Вікно часу
  | 'pomodoro'    // Pomodoro
  | 'habit'       // Звичка
  | 'kid'         // Дитяча
  | 'ctx'         // Контекст
  | 'negative'    // Шкідлива звичка (віднімає XP)
  | 'zonelinked'; // Прив'язана до зони

export interface ChecklistItem {
  text: string;
  done: boolean;
}

/**
 * Задача. НЕ чистий discriminated union навмисно: оригінальний mkTask()
 * створює один об'єкт і заповнює лише релевантні полю типу поля (решта —
 * undefined), і рендер-функції по всьому коду читають `t.foo` одразу після
 * перевірки `t.type==='foo-type'`. Точне звуження на discriminated union —
 * можлива майбутня чистка (окремо від Кроку 1), але зараз пріоритет —
 * побітова сумісність із existing localStorage/Room-даними користувачів.
 */
export interface Task {
  id: number;
  title: string;
  type: TaskType;
  done: boolean;
  someday: boolean;
  zoneId?: number | null;
  zoneColor?: string | null;
  zoneName?: string | null;
  /** Прив'язка задачі до папки нотаток (окремо від zoneId) */
  folderId?: string | null;
  expanded?: boolean;
  created: string; // ISO
  trashed?: boolean;

  // done-стан
  doneDate?: string;   // "YYYY-MM-DD" (fmtDate)
  doneAt?: string;     // "HH:MM"
  snoozeUntil?: number; // epoch ms, task_skip / відкладання

  // type === 'check'
  items?: ChecklistItem[];

  // type === 'counter' | 'negative'
  counter?: number;
  counterTarget?: number; // тільки 'counter'
  /** Разовий бонус XP уже нараховано за досягнення цілі (chgCnt) */
  cntHit?: boolean;
  negXp?: number;         // тільки 'negative' — скільки XP віднімається за клік

  // type === 'note'
  note?: string;

  // type === 'alarm'
  alarmTime?: string;   // "HH:MM"
  alarmFired?: boolean;

  // type === 'sched'
  schedDate?: string;   // "YYYY-MM-DD"
  schedTime?: string;   // "HH:MM"
  firedSched?: boolean;
  firedPre?: boolean;   // передвісник "за день до" вже спрацював

  // type === 'timewin'
  windowStart?: string;
  windowEnd?: string;
  completedToday?: boolean;

  // type === 'pomodoro'
  pomSecs?: number;
  pomMode?: 'work' | 'break';
  pomSessions?: number;
  pomRunning?: boolean;

  // type === 'habit'
  habitDays?: boolean[]; // 7 елементів, Пн..Нд

  // type === 'kid'
  kidStars?: number;
  kidDiff?: 'easy' | 'mid' | 'hard';
  kidReward?: string;

  // type === 'ctx'
  ctxTags?: string[];

  // type === 'zonelinked'
  zoneDoneToday?: boolean;

  // спільне для повторюваних задач будь-якого типу
  repeat: 'none' | 'daily' | 'weekly' | 'weekdays' | 'weekend' | 'everyzone' | 'interval' | 'custom';
  repeatDays: boolean[]; // 7 елементів, Пн..Нд
  tags: string[];
  // type==='interval' повторення
  repeatMs?: number;
  nextRepeatAt?: number; // epoch ms
  /** Користувацькі поля редактора для repeat==='interval'; repeatMs похідне від них */
  repeatInterval?: number;
  repeatUnit?: 'sec' | 'min' | 'hour' | 'day' | 'week' | 'month';

  // плановані на конкретний день з планувальника (не плутати з planItems!)
  planDate?: string;

  // м'яке видалення (кошик)
  trashedAt?: number | null;
}

// ─────────────────────────────────────────────────────────────────────────
// РУТИНА / RECUR  (оригінал: рядки 1180–1184)
// ─────────────────────────────────────────────────────────────────────────

export interface RecurItem {
  id: string;
  nm: string;
  color: string;
  unit: 'check' | 'count' | 'ml' | 'min' | 'kcal';
  val: number;
  done: boolean;
  step: number;
  /** Шкідлива звичка (наприклад «Перекур») — інвертована логіка нагороди */
  neg: boolean;
  negXp: number;
}

// ─────────────────────────────────────────────────────────────────────────
// НОТАТКИ / ПАПКИ  (оригінал: mkNote() рядки 1257–1263, folders рядки 1192–1197)
// ─────────────────────────────────────────────────────────────────────────

export interface Folder {
  id: string;
  nm: string;
  ico: string;
}

export interface QuickNote {
  id: number;
  txt: string;
  folder: string;
  time: string; // локалізований "HH:MM"
  date: string; // "YYYY-MM-DD" (fmtDate) — потрібно для аналізу в планувальнику
}

// ─────────────────────────────────────────────────────────────────────────
// ГЕЙМІФІКАЦІЯ  (оригінал: рядки 1546–1604, FACHS рядки 1247–1254)
// ─────────────────────────────────────────────────────────────────────────

export type EnergyLevel = 0 | 1 | 2 | 3 | 4 | 5;

/** Контекст, потрібний функціям-перевіркам досягнень (FACHS[].fn) */
export interface AchievementCheckContext {
  done: number;
  xpTotal: number;
  level: number;
  zonesCount: number;
}

export interface Achievement {
  id: string;
  ico: string;
  nm: string;
  check: (ctx: AchievementCheckContext) => boolean;
}

export interface RareEvent {
  id: number;
  nm: string;
  val: number;
  dir: 'up' | 'down';
  unit: string;
}

export interface FocusLogEntry {
  val: string;
  start: string; // ISO
  end: string | null; // ISO, null поки триває
  date: string; // "YYYY-MM-DD"
  time: string; // "HH:MM"
  dur?: number; // хвилини, проставляється при закритті запису
}

// ─────────────────────────────────────────────────────────────────────────
// ПЛАНУВАЛЬНИК  (оригінал: рядки 1224–1235, 3313–3921)
// Ці карти НАВМИСНО живуть окремо від основного стану `S` в оригіналі —
// у новому Store вони об'єднуються як окремий slice (planner), але
// зберігають ідентичну форму ключів для сумісності зі старими бекапами.
// ─────────────────────────────────────────────────────────────────────────

/** Ключ — дата "YYYY-MM-DD" */
export type DateKey = string;

export interface PlanItem {
  title: string;
  done: boolean;
  isEvent?: boolean;
  time?: string;
}

export interface PlanDayZoneOverride {
  zoneId: number;
  s: string;
  e: string;
  /** Позначка "лише цей день" — editDailyZoneForDay() (рядок 3653) */
  ov?: boolean;
}

export interface PlanRuleDay {
  tplId: string;
  rest: boolean;
}

export interface PlanRule {
  id: string;
  name: string;
  start: DateKey;
  end: DateKey;
  days: PlanRuleDay[];
}

export interface DayLogEntry {
  focus: string[];
  energy: number;
  /** unit==='check' → boolean; інші одиниці → рядок "150 мл" тощо (logDayData()) */
  routineDone: Record<string, string | boolean>;
  priorities: unknown[];
  tasksDone: number;
  note?: string;
}

/**
 * Блок шаблону дня. Форма виведена з контексту використання (dayBlocks(),
 * КОНСТРУКТОР ТИЖНЯ) — уточнити побітово на Кроці «Планувальник».
 */
export interface DayTemplateBlock {
  zoneId: number;
  s: string;
  e: string;
}

export interface DayTemplate {
  id: string;
  name: string;
  blocks: DayTemplateBlock[];
}

/**
 * Шаблон тижня (конструктор 5/2, 2/2, 3/3). Внутрішня форма ще не звірена
 * побітово з оригіналом (рядки 3683–3921) — навмисно `unknown`-толерантна,
 * щоб round-trip JSON.parse/stringify під час Кроку 1 нічого не губив,
 * поки повна структура не перенесена разом із КОНСТРУКТОРОМ ТИЖНЯ.
 */
export interface WeekTemplate {
  id: string;
  name: string;
  days: PlanRuleDay[];
}

export interface PlannerState {
  planItems: Record<DateKey, PlanItem[]>;
  planRestDays: Record<DateKey, boolean>;
  planSchedules: Record<DateKey, string>; // dateKey -> DayTemplate.id
  planDayZones: Record<DateKey, PlanDayZoneOverride[]>;
  planDayOff: Record<DateKey, number[]>; // dateKey -> zoneId[]
  planRules: PlanRule[];
  planDayLog: Record<DateKey, DayLogEntry>;
  /**
   * У оригіналі жили на верхньому рівні `S` (рядок 1198), а не серед
   * plan*-змінних — органічний артефакт росту коду. Логічно це той самий
   * домен «планувальник», тож тут згруповано разом. AppState все одно
   * розкладає це в ту саму пласку форму JSON через `extends`, тому
   * localStorage/Room-сумісність не порушується.
   */
  dayTemplates: DayTemplate[];
  weekTemplates: WeekTemplate[];
  weekTplSeeded: boolean;
}

// ─────────────────────────────────────────────────────────────────────────
// ПОВНИЙ СТАН ЗАСТОСУНКУ  (оригінал: collectState()/applyState(), рядки 4258–4348)
// Це ТОЧНА форма JSON, що йде у localStorage("flow_v2") ТА в Android Room
// через window.FlowBridge.save(). Будь-яка зміна імені поля тут = втрата
// даних для існуючих користувачів при першому запуску нової версії.
// ─────────────────────────────────────────────────────────────────────────

export interface NotifTaskListItem {
  id: number;
  title: string;
  type: TaskType;
  counter: number;
  counterTarget: number;
}

export interface NotifRoutineListItem {
  id: string;
  nm: string;
  ico: string;
  unit: string;
  val: number;
  step: number;
  done: boolean;
}

export interface NotifSchedListItem {
  id: number | string;
  title: string;
  dueMs: number;
  fired: boolean;
}

export interface NotifZoneTimelineItem {
  id: number;
  s: string;
  e: string;
  nm: string;
  desc: string;
  color: string;
  prio: number;
}

/** fgNotifText() — рядки 3948–4097. Payload і для Android foreground-сервісу, і для Web Notification API. */
export interface ForegroundNotifPayload {
  zone: string;
  zoneColor: string;
  zoneTimeline: NotifZoneTimelineItem[];
  zoneBaseline: NotifZoneTimelineItem[];
  zoneTasksById: Record<number, string[]>;
  zonelessTasks: string[];
  urgentTasks: string[];
  tlDate: string;
  slots: string;
  desc: string;
  tasks: string;
  taskList: NotifTaskListItem[];
  routine: string;
  routineList: NotifRoutineListItem[];
  schedList: NotifSchedListItem[];
  body: string;
  time: string;
  done: number;
  total: number;
  energy: number;
  title: string;
}

export interface AppState extends PlannerState {
  tasks: Task[];
  recur: RecurItem[];
  zones: Zone[];
  folders: Folder[];
  qnotes: QuickNote[];

  xp: number;
  xpTotal: number;
  level: number;
  done: number;
  streak: number;
  negCount: number;
  nid: number;
  unlocked: string[];
  energy: EnergyLevel;
  lastEnergyXp: number;
  dayXpGained: number;
  xpDay: string;

  priorities: (number | null)[]; // довжина 3
  focusChips: string[];
  focusLog: FocusLogEntry[];
  currentFocus: string;

  /**
   * Рендериться у вкладці «Статистика» поруч із досягненнями (НЕ в планувальнику,
   * попри назву) — точний домен-власник уточнюється; уточнюється на Кроці
   * «Планувальник» (РІДКІСНІ ПОДІЇ, рядок 2556).
   */
  rareEvents: RareEvent[];

  notepad: string;
  theme: 'light' | 'dark';

  /** Додається лише при saveState(), не є частиною логічного стану */
  saveDate?: string;
}
