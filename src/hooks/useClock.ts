import { useEffect, useState } from 'react';
import { nowHM } from '../utils/date';

/**
 * useClock() — відповідник тік-частини updateClock() (www/index.html,
 * рядок 1428), рядок 3055 (setInterval 60000). Компоненти, яким потрібен
 * "поточний час", підписуються тут — при зміні store вони й так
 * перемальовуються (getActiveZones викликається напряму в рендері), цей
 * хук потрібен лише щоб перемальовка ставалась і КОЛИ store не змінювався,
 * а просто минула хвилина (наприклад — зайшли в нову зону).
 *
 * TODO: S.liveMode/S.debugTime (debug-бар, рядки 219–224 CSS) — режим
 * "заморозити час на значенні" для розробки, ще не перенесено. Зараз
 * завжди використовується реальний час.
 */
export function useClock() {
  const [hm, setHm] = useState(nowHM());
  useEffect(() => {
    const id = setInterval(() => setHm(nowHM()), 60000);
    return () => clearInterval(id);
  }, []);
  return hm;
}
