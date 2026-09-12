import { create, type StateCreator } from 'zustand';

import { createGamificationSlice, type GamificationSlice } from './slices/gamificationSlice';
import { createRoutineSlice, type RoutineSlice } from './slices/routineSlice';
import { createTasksSlice, type TasksSlice } from './slices/tasksSlice';
import { createZonesSlice, type ZonesSlice } from './slices/zonesSlice';
import { createNotesSlice, type NotesSlice } from './slices/notesSlice';
import { createPlannerSlice, type PlannerSlice } from './slices/plannerSlice';
import { createFocusSlice, type FocusSlice } from './slices/focusSlice';
import { createUiSlice, type UiSlice } from './slices/uiSlice';
import { createLifecycleSlice, type LifecycleSlice } from './slices/lifecycleSlice';

export type RootState = GamificationSlice &
  RoutineSlice &
  TasksSlice &
  ZonesSlice &
  NotesSlice &
  PlannerSlice &
  FocusSlice &
  UiSlice &
  LifecycleSlice;

/**
 * Тип-хелпер для кожного slice-файлу: `AppSlice<ThisSliceInterface>`.
 * Циклічний імпорт (store.ts <-> slices/*.ts) працює нормально, бо це
 * ЛИШЕ типи (`import type`), вони стираються при компіляції — це той самий
 * патерн, що й в офіційній документації Zustand для "slices pattern".
 */
export type AppSlice<T> = StateCreator<RootState, [], [], T>;

export const useStore = create<RootState>()((...a) => ({
  ...createGamificationSlice(...a),
  ...createRoutineSlice(...a),
  ...createTasksSlice(...a),
  ...createZonesSlice(...a),
  ...createNotesSlice(...a),
  ...createPlannerSlice(...a),
  ...createFocusSlice(...a),
  ...createUiSlice(...a),
  ...createLifecycleSlice(...a),
}));
