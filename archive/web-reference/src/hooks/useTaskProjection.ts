import { useMemo } from 'react';
import { useClock } from './useClock';
import { useStore } from '../state/store';
import { buildQuestTimeline } from '../utils/questPolicy';
import { fmtDate } from '../utils/date';

/** Calendar previews and quest cards read the same effective zone schedule. */
export function useTaskProjection() {
  const hm = useClock();
  const zones = useStore(s => s.zones);
  const planDayOff = useStore(s => s.planDayOff);
  const planDayZones = useStore(s => s.planDayZones);
  const planRules = useStore(s => s.planRules);
  const dayTemplates = useStore(s => s.dayTemplates);
  const dayBlocks = useStore(s => s.dayBlocks);
  const now = new Date();
  const day = fmtDate(now);
  const timeline = useMemo(() => buildQuestTimeline({ zones, planDayOff, dayBlocks, now: new Date() }),
    [day, hm.h, hm.m, zones, planDayOff, planDayZones, planRules, dayTemplates, dayBlocks]);
  return { now, timeline };
}
