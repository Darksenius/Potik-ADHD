import type { PlanRule, DateKey, PlanDayZoneOverride, DayTemplate } from '../../types';
import { addDaysDs, daysBetweenDs } from '../../utils/date';

/** ruleFor() — рядки 3331–3335. Останнє (найновіше) правило, що покриває день. */
export function ruleFor(rules: PlanRule[], ds: DateKey): PlanRule | null {
  let r: PlanRule | null = null;
  (rules || []).forEach((x) => {
    if (ds >= x.start && ds <= x.end) r = x;
  });
  return r;
}

/** ruleIdx() — рядки 3337–3340. Індекс дня в циклі правила. */
export function ruleIdx(r: PlanRule, ds: DateKey): number {
  if (r.days.length === 7) return (new Date(ds + 'T12:00:00').getDay() + 6) % 7;
  return ((daysBetweenDs(r.start, ds) % r.days.length) + r.days.length) % r.days.length;
}

/** ruleEntry() — рядок 3341. */
export function ruleEntry(r: PlanRule | null, ds: DateKey) {
  return r ? r.days[ruleIdx(r, ds)] : null;
}

/** dayBlocks() — рядки 3343–3349. Ефективні зони дня: ручні правки сильніші за правило. */
export function dayBlocksFor(
  ds: DateKey,
  planDayZones: Record<DateKey, PlanDayZoneOverride[]>,
  planRules: PlanRule[],
  dayTemplates: DayTemplate[]
): PlanDayZoneOverride[] {
  if (planDayZones[ds]) return planDayZones[ds];
  const e = ruleEntry(ruleFor(planRules, ds), ds);
  if (!e || !e.tplId) return [];
  const tp = (dayTemplates || []).find((t) => t.id === e.tplId);
  return tp ? (tp.blocks || []).map((b) => ({ zoneId: b.zoneId, s: b.s, e: b.e })) : [];
}

/** dayIsRest() — рядки 3351–3355. Ручний прапорець сильніший за правило. */
export function dayIsRestFor(
  ds: DateKey,
  planRestDays: Record<DateKey, boolean>,
  planRules: PlanRule[]
): boolean {
  if (planRestDays[ds] !== undefined && planRestDays[ds] !== null) return !!planRestDays[ds];
  const e = ruleEntry(ruleFor(planRules, ds), ds);
  return !!(e && e.rest);
}

export { addDaysDs };
