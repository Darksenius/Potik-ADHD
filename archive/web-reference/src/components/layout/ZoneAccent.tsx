import { useEffect } from 'react';
import { useStore } from '../../state/store';
import { useClock } from '../../hooks/useClock';

/** The active zone colors every screen, including when the task list is closed. */
export default function ZoneAccent() {
  const hm = useClock();
  useStore(s => s.zones);
  useStore(s => s.planDayZones);
  useStore(s => s.planDayOff);
  useStore(s => s.planRules);
  useStore(s => s.dayTemplates);
  const color = useStore.getState().getActiveZones(hm)[0]?.color || '#7ed321';
  useEffect(() => {
    document.documentElement.style.setProperty('--z', color);
  }, [color]);
  return null;
}
