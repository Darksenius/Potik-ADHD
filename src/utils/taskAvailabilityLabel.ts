import type { Task } from '../types';
import type { QuestAvailability, QuestContext, QuestUnavailableReason } from './questPolicy';
import { fmtDate, normalizeTime, toMinutes } from './date';
import { repeatDueOn, taskPlanDate } from './taskSchedule';

export type TaskAvailabilityAction = { label: 'Налаштувати зону' | 'Відкрити План'; tab: 'zones' | 'planner' };
export type TaskAvailabilityExplanation = { message: string; actions?: TaskAvailabilityAction[] };

function formatDate(day: string): string {
  const date = new Date(day + 'T12:00:00');
  return Number.isFinite(date.getTime())
    ? date.toLocaleDateString('uk', { weekday: 'long', day: 'numeric', month: 'long' })
    : day;
}

function formatMoment(at: number): string {
  const date = new Date(at);
  return Number.isFinite(date.getTime())
    ? date.toLocaleString('uk', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
    : '';
}

function zoneName(id: number | null | undefined, context: QuestContext): string | undefined {
  if (id == null || id === 0) return undefined;
  return context.zones.find(zone => zone.id === id)?.nm;
}

function action(label: TaskAvailabilityAction['label']): TaskAvailabilityAction {
  return { label, tab: label === 'Відкрити План' ? 'planner' : 'zones' };
}

function nextRepeatDate(task: Task, now: Date): string | undefined {
  const date = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 12);
  for (let offset = 0; offset < 8; offset++) {
    if (repeatDueOn(task, date)) return fmtDate(date);
    date.setDate(date.getDate() + 1);
  }
  return undefined;
}

function repeatExplanation(task: Task, availability: QuestAvailability, context: QuestContext): string {
  if (availability.availableAt && availability.availableAt > context.now.getTime()) {
    return `Наступне повторення доступне ${formatMoment(availability.availableAt)}.`;
  }
  const nextDate = nextRepeatDate(task, context.now);
  return nextDate
    ? `Повторення не припадає на сьогодні. Наступний день за розкладом — ${formatDate(nextDate)}.`
    : 'Повторення не припадає на сьогодні. Перевір дні та інтервал повторення.';
}

function nextTimeWindow(task: Task, now: Date): number | undefined {
  const start = normalizeTime(task.windowStart);
  const end = normalizeTime(task.windowEnd);
  if (!start || !end) return undefined;
  const from = toMinutes(start);
  const to = toMinutes(end);
  if (!Number.isFinite(from) || !Number.isFinite(to)) return undefined;
  const next = new Date(now);
  next.setSeconds(0, 0);
  next.setHours(Math.floor(from / 60), from % 60);
  if (next.getTime() <= now.getTime()) next.setDate(next.getDate() + 1);
  for (let offset = 0; offset < 8; offset++) {
    if (repeatDueOn(task, next)) return next.getTime();
    next.setDate(next.getDate() + 1);
  }
  return undefined;
}

function wrongZoneExplanation(task: Task, context: QuestContext): TaskAvailabilityExplanation {
  const deferred = task.questDeferral;
  const now = context.now.getTime();
  const returned = deferred?.kind === 'next-zone' && deferred.endAt !== undefined && now >= deferred.endAt;
  const requestedZoneId = deferred?.kind === 'next-zone'
    ? returned ? deferred.sourceZoneId : deferred.targetZoneId
    : task.zoneId;
  const zone = requestedZoneId == null ? undefined : context.zones.find(item => item.id === requestedZoneId);
  if (!zone) {
    return {
      message: 'Потрібної зони немає у списку. Перевір призначення зони або створи її.',
      actions: [action('Налаштувати зону')],
    };
  }
  if (zone.off || zone.active === false) {
    return {
      message: `Зону «${zone.nm}» вимкнено, тому задача зараз недоступна. Увімкни або налаштуй цю зону.`,
      actions: [action('Налаштувати зону')],
    };
  }

  const targetZoneId = requestedZoneId;
  const lowerBound = Math.max(now, returned ? deferred!.endAt! : deferred?.startAt || now);
  const next = context.timeline.find(segment => segment.zoneId === targetZoneId && segment.endAt > lowerBound && segment.startAt > now);
  if (next) {
    const moment = formatMoment(Math.max(next.startAt, lowerBound));
    return { message: `Задача доступна в зоні «${zone.nm}». Наступний запланований проміжок почнеться ${moment}.` };
  }
  return {
    message: `Задача доступна в зоні «${zone.nm}», але в найближчому розкладі для неї немає запланованого проміжку. Додай час цієї зони в Плані або зміни її налаштування.`,
    actions: [action('Відкрити План'), action('Налаштувати зону')],
  };
}

function outsideWindowExplanation(task: Task, context: QuestContext): string {
  const start = normalizeTime(task.windowStart);
  const end = normalizeTime(task.windowEnd);
  if (!start || !end) return 'Задача зараз поза часовим вікном. Перевір налаштування проміжку.';
  const next = nextTimeWindow(task, context.now);
  return next
    ? `Часове вікно задачі: з ${start} до ${end}. Наступне вікно за днями повторення почнеться ${formatMoment(next)}.`
    : `Задача доступна з ${start} до ${end}; поточний час поза цим вікном.`;
}

export function describeTaskAvailability(
  task: Task,
  availability: QuestAvailability,
  context: QuestContext,
): TaskAvailabilityExplanation | undefined {
  if (availability.eligible || !availability.reason) return undefined;

  const reason: QuestUnavailableReason = availability.reason;
  switch (reason) {
    case 'done': return { message: 'Цю задачу вже виконано для поточного повторення.' };
    case 'journal': return { message: 'Це запис у журналі подій, а не задача для виконання.' };
    case 'trashed': return { message: 'Задача перебуває в кошику.' };
    case 'someday': return { message: 'Задачу відкладено на «Колись», тому зараз вона не входить до поточного списку.' };
    case 'future-date': {
      const day = taskPlanDate(task);
      const time = task.type === 'sched' ? task.schedTime : undefined;
      return { message: day ? `Задача запланована на ${formatDate(day)}${time ? ` о ${time}` : ''}.` : 'Задача має майбутню дату в Плані.' };
    }
    case 'snoozed':
    case 'deferred-until': {
      const at = availability.availableAt;
      const zone = task.questDeferral ? zoneName(task.questDeferral.targetZoneId, context) : undefined;
      return { message: at ? `Задача відкладена до ${formatMoment(at)}${zone ? ` у зоні «${zone}»` : ''}.` : 'Задача відкладена; час повернення ще не настав.' };
    }
    case 'not-due': {
      if (availability.availableAt && availability.availableAt > context.now.getTime()) {
        const reason = task.repeat === 'interval' ? 'Наступне повторення' : task.type === 'sched' || task.type === 'alarm' ? 'Запланований час' : 'Задача';
        return { message: `${reason} настане ${formatMoment(availability.availableAt)}.` };
      }
      return { message: repeatExplanation(task, availability, context) };
    }
    case 'outside-time-window': return { message: outsideWindowExplanation(task, context) };
    case 'timewin-completed': return { message: 'Задачу з часовим вікном уже виконано сьогодні.' };
    case 'missing-zone': {
      const oldName = task.zoneName || (task.zoneId == null ? '' : `№${task.zoneId}`);
      return {
        message: `Призначену зону${oldName ? ` «${oldName}»` : ''} видалено. Вибери наявну зону або створи нову.`,
        actions: [action('Налаштувати зону')],
      };
    }
    case 'unassigned-zone':
      return { message: 'Для цієї задачі ще не вибрано зону.', actions: [action('Налаштувати зону')] };
    case 'wrong-zone': return wrongZoneExplanation(task, context);
    default: return { message: 'Задача зараз недоступна за своїми умовами.' };
  }
}
