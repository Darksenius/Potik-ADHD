import type { MouseEvent as ReactMouseEvent } from 'react';
import { useStore } from '../state/store';

type RewardTap = ReactMouseEvent<HTMLButtonElement>;

/** Runs a task completion action and emits only the positive XP delta at its tap point. */
export function runTaskCompletionWithReward(event: RewardTap, complete: () => void): void {
  const before = useStore.getState().xpTotal;
  const rect = event.currentTarget.getBoundingClientRect();
  const x = event.clientX || rect.left + rect.width / 2;
  const y = event.clientY || rect.top + rect.height / 2;
  complete();
  if (useStore.getState().preferences.showGamification === false) return;
  const amount = Math.max(0, useStore.getState().xpTotal - before);
  if (amount <= 0) return;
  window.dispatchEvent(new CustomEvent<{ amount: number; x: number; y: number }>('potik:task-reward', {
    detail: { amount, x, y },
  }));
}
