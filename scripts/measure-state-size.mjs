// Deterministic illustrative storage fixture; this script does not read or write app data.
const DAYS = 365;
const CHECKLIST_ITEMS = 5;
const DAY_MS = 24 * 60 * 60 * 1000;
const start = Date.UTC(2025, 0, 1);
const dateKey = (offset) => new Date(start + offset * DAY_MS).toISOString().slice(0, 10);

const taskHistory = [];
const focusLog = [];
const planDayLog = {};

for (let day = 0; day < DAYS; day += 1) {
  const date = dateKey(day);
  const at = start + day * DAY_MS;
  for (let index = 0; index < 4; index += 1) {
    const occurrenceId = `task-${index + 1}@2024-01-01|day:${date}`;
    const event = {
      id: `${occurrenceId}#${index}:${at + index * 60_000}:completed`,
      occurrenceId,
      taskId: index + 1,
      taskCreated: '2024-01-01T09:00:00.000Z',
      title: ['Ранкова рутина', 'Прочитати', 'Руханка', 'Підсумок дня'][index],
      type: index === 2 ? 'habit' : 'simple',
      kind: 'completed',
      sequence: day * 4 + index,
      at: at + index * 60_000,
      date,
      time: `0${8 + index}:00`,
      zoneId: index < 2 ? 1 : null,
      counter: index === 2 ? 20 : undefined,
    };
    // One of four daily events belongs to a checklist task with five steps.
    if (index === 0) {
      event.items = Array.from({ length: CHECKLIST_ITEMS }, (_, item) => ({
        text: `Крок ${item + 1}: виконати частину рутини`, done: true, credited: true,
      }));
    }
    taskHistory.push(event);
  }

  for (let session = 0; session < 2; session += 1) {
    const hour = session === 0 ? 9 : 14;
    focusLog.push({
      val: session === 0 ? 'Підготовка' : 'Зосереджена робота',
      start: `${date}T${String(hour).padStart(2, '0')}:00:00.000Z`,
      end: `${date}T${String(hour).padStart(2, '0')}:25:00.000Z`,
      date,
      time: `${String(hour).padStart(2, '0')}:00`,
      dur: 25,
    });
  }

  planDayLog[date] = {
    focus: ['Підготовка', 'Зосереджена робота'],
    energy: day % 5 + 1,
    routineDone: { water: true, movement: day % 2 === 0 },
    priorities: ['Робочий пріоритет'],
    tasksDone: 4,
    note: 'План дня виконано; коротка нотатка про самопочуття.',
  };
}

const before = { taskHistory, focusLog, planDayLog };
const after = {
  taskHistory: taskHistory.map((event) => {
    const { items, ...withoutItems } = event;
    return withoutItems;
  }),
  focusLog,
  planDayLog,
};
const bytes = (value) => Buffer.byteLength(JSON.stringify(value), 'utf8');
const fields = Object.keys(before);
const report = Object.fromEntries(fields.map((field) => [field, {
  beforeBytes: bytes(before[field]),
  afterBytes: bytes(after[field]),
  savedBytes: bytes(before[field]) - bytes(after[field]),
}]));
const wholeBefore = bytes(before);
const wholeAfter = bytes(after);

console.log('Illustrative deterministic fixture (UTF-8 JSON bytes)');
console.log(`Days: ${DAYS}; taskHistory events: ${taskHistory.length}; focusLog sessions: ${focusLog.length}; planDayLog days: ${Object.keys(planDayLog).length}`);
console.log(`Checklist snapshot assumption: ${CHECKLIST_ITEMS} items on one of four task events per day.`);
for (const field of fields) {
  const row = report[field];
  console.log(`${field}: before ${row.beforeBytes}; after ${row.afterBytes}; saved ${row.savedBytes}`);
}
console.log(`whole selected state: before ${wholeBefore}; after ${wholeAfter}; saved ${wholeBefore - wholeAfter}`);
