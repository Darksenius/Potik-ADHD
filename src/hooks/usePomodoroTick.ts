import { useEffect } from 'react';
import { useStore } from '../state/store';

/**
 * Тіло Pomodoro (tickPomodoro) готове в tasksSlice, але щось має викликати
 * його раз/сек. Оригінал це робив через setInterval у togPom() (рядок 2264).
 * Тут — один спільний інтервал на весь застосунок (не по інтервалу на
 * кожну задачу), викликається з App.tsx один раз.
 */
export function usePomodoroTick() {
  useEffect(() => {
    const id = setInterval(() => {
      const s = useStore.getState();
      s.tasks.forEach((t) => {
        if (t.type === 'pomodoro' && t.pomRunning) s.tickPomodoro(t.id);
      });
    }, 1000);
    return () => clearInterval(id);
  }, []);
}
