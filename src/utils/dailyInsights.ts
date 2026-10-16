import content from '../content/daily-insights.json' with { type: 'json' };
import { fmtDate } from './date';

export interface DailyInsightClaim {
  day: string;
  cardId: number;
  sequence: number;
  /** Zero marks an archived day seeded on upgrade, without a new notification. */
  earnedAt: number;
}

export const DAILY_INSIGHTS = content.cards;
export const INSIGHT_SOURCES = content.sources;

export function insightForClaim(claim: DailyInsightClaim) {
  return DAILY_INSIGHTS.find(card => card.id === claim.cardId);
}

function validDay(day: string, today: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(day) && day <= today
    && fmtDate(new Date(day + 'T12:00:00')) === day;
}

/** Preserve earned cards when imports add earlier activity dates. Opening the app earns nothing. */
export function archiveInsightClaims(days: readonly string[], claims: readonly DailyInsightClaim[] = [], now = Date.now()): DailyInsightClaim[] {
  const today = fmtDate(new Date(now));
  const active = new Set(days);
  const byDay = new Map<string, DailyInsightClaim>();
  for (const claim of claims) {
    if (!claim || !active.has(claim.day) || !validDay(claim.day, today) || byDay.has(claim.day)
      || !Number.isSafeInteger(claim.cardId) || claim.cardId < 1 || claim.cardId > DAILY_INSIGHTS.length
      || !Number.isSafeInteger(claim.sequence) || claim.sequence < 1
      || !Number.isFinite(claim.earnedAt) || claim.earnedAt < 0) continue;
    byDay.set(claim.day, claim);
  }
  let sequence = Math.max(0, ...[...byDay.values()].map(claim => claim.sequence));
  for (const day of [...active].sort()) {
    if (day >= today || !validDay(day, today) || byDay.has(day)) continue;
    sequence += 1;
    byDay.set(day, { day, sequence, cardId: (sequence - 1) % DAILY_INSIGHTS.length + 1, earnedAt: 0 });
  }
  const normalized = [...byDay.values()].sort((a, b) => a.day.localeCompare(b.day));
  return normalized.length === claims.length && normalized.every((claim, index) => claim === claims[index])
    ? claims as DailyInsightClaim[] : normalized;
}

/** One card for the first qualifying action today. A break does not reset the collection. */
export function claimDailyInsight(days: readonly string[], claims: readonly DailyInsightClaim[], at = Date.now(), now = Date.now()) {
  const today = fmtDate(new Date(now));
  const archived = archiveInsightClaims(days, claims, now);
  const day = Number.isFinite(at) ? fmtDate(new Date(at)) : '';
  if (day !== today || !days.includes(day) || archived.some(claim => claim.day === day)) {
    return { claims: archived, earned: null };
  }
  const sequence = Math.max(days.length, ...archived.map(claim => claim.sequence + 1), 1);
  const earned: DailyInsightClaim = { day, sequence,
    cardId: (sequence - 1) % DAILY_INSIGHTS.length + 1, earnedAt: now };
  return { claims: [...archived, earned].sort((a, b) => a.day.localeCompare(b.day)), earned };
}
