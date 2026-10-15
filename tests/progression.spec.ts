import { test, expect } from '@playwright/test';
import { useStore } from '../src/state/store';
import {
  COLLECTIBLE_TOKENS,
  initializeProgression,
  progressionFromXp,
  recordProgressDay,
  tokensEarnedForActiveDays,
} from '../src/utils/progression';
import { fmtDate } from '../src/utils/date';

function dayAtOffset(offset: number): { day: string; at: number } {
  const date = new Date();
  date.setHours(12, 0, 0, 0);
  date.setDate(date.getDate() + offset);
  return { day: fmtDate(date), at: date.getTime() };
}

test.beforeEach(() => {
  useStore.setState(useStore.getInitialState(), true);
  useStore.getState().initializeProgression();
});

test('legacy XP balance migrates once without changing lifetime XP', () => {
  const legacy = {
    progressionVersion: 0,
    level: 4,
    xp: 50,
    xpTotal: 731,
    progressDays: [],
  };
  const migrated = initializeProgression(legacy);

  expect(migrated).toMatchObject({ progressionVersion: 2, level: 6, xp: 100, xpTotal: 731 });
  expect(initializeProgression({ ...legacy, ...migrated })).toEqual(migrated);
});

test('100 active days survive gaps and duplicates while future dates are ignored', () => {
  const activeDays = Array.from({ length: 100 }, (_, index) => dayAtOffset(-200 + index * 2).day);
  const future = dayAtOffset(1).day;
  const notes = activeDays.map((date, index) => ({ id: index + 1, txt: 'Запис', folder: '', time: '12:00', date }));
  const migrated = initializeProgression({
    progressionVersion: 2,
    level: 1,
    xp: 0,
    xpTotal: 0,
    progressDays: [activeDays[0], activeDays[0], future],
    qnotes: [...notes, { ...notes[0], id: 101 }, { id: 102, txt: 'Майбутнє', folder: '', time: '12:00', date: future }],
  });

  expect(migrated.progressDays).toHaveLength(100);
  expect(tokensEarnedForActiveDays(migrated.progressDays.length)).toHaveLength(20);
  expect(COLLECTIBLE_TOKENS.map((token) => token.day)).toEqual(Array.from({ length: 20 }, (_, index) => (index + 1) * 5));
  expect(recordProgressDay(migrated.progressDays, dayAtOffset(1).at)).toEqual(migrated.progressDays);
  expect(recordProgressDay(migrated.progressDays, dayAtOffset(-200).at)).toBe(migrated.progressDays);
});

test('award lazily migrates a legacy balance before applying XP', () => {
  const today = new Date().toDateString();
  useStore.setState({ progressionVersion: 0, level: 4, xp: 50, xpTotal: 731, xpDay: today, dayXpGained: 0 });

  useStore.getState().award(1);

  expect(useStore.getState()).toMatchObject({ progressionVersion: 2, level: 6, xp: 101, xpTotal: 732 });
});

test('negative XP does not reopen the daily positive award cap', () => {
  const today = new Date().toDateString();
  useStore.setState({ xpDay: today, dayXpGained: 0, xp: 0, xpTotal: 0, level: 1 });

  useStore.getState().award(250);
  const afterCap = useStore.getState();
  useStore.getState().award(-50);
  useStore.getState().award(50);

  expect(useStore.getState()).toMatchObject({
    dayXpGained: 250,
    xpTotal: 250,
    level: afterCap.level,
  });
  expect(useStore.getState().xp).toBe(Math.max(0, afterCap.xp - 50));
});

test('enormous finite XP resolves in bounded time without lowering earned level', () => {
  const huge = 1e100;
  expect(progressionFromXp(huge).level).toBe(Number.MAX_SAFE_INTEGER);

  useStore.setState({ progressionVersion: 2, level: 12, xp: huge, xpTotal: huge, xpDay: new Date().toDateString(), dayXpGained: 0 });
  useStore.getState().award(1);

  expect(useStore.getState().level).toBe(Number.MAX_SAFE_INTEGER);
  expect(Number.isFinite(useStore.getState().xp)).toBe(true);
});
