import { test, expect } from '@playwright/test';
import { useStore } from '../src/state/store';

test('reselecting the active focus keeps one open interval and removing it closes that interval', () => {
  useStore.setState({ currentFocus: '', focusLog: [], focusChips: ['Робота'], xpTotal: 23 });
  useStore.getState().setFocus('Робота');
  const first = useStore.getState().focusLog;
  expect(first).toHaveLength(1);
  expect(first[0].end).toBeNull();
  useStore.getState().setFocus('Робота');
  useStore.getState().addFocusChip('Робота');
  expect(useStore.getState().focusLog).toEqual(first);
  useStore.getState().removeFocusChip('Робота');
  expect(useStore.getState().currentFocus).toBe('');
  expect(useStore.getState().focusLog).toHaveLength(1);
  expect(useStore.getState().focusLog[0].end).toBeTruthy();
  expect(useStore.getState().xpTotal).toBe(23);
});
