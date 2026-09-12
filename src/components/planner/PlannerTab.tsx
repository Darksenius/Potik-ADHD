import { useEffect } from 'react';
import { useStore } from '../../state/store';
import { getWeekStart } from '../../state/slices/plannerSlice';
import { fmtDate } from '../../utils/date';
import WeekGrid from './WeekGrid';
import WeekConstructor from './WeekConstructor';
import DayDetail from './DayDetail';

export default function PlannerTab() {
  const weekOffset = useStore((s) => s.planWeekOffset);
  const shiftPlanWeek = useStore((s) => s.shiftPlanWeek);
  const selectedDay = useStore((s) => s.planSelectedDay);
  const setPlanSelectedDay = useStore((s) => s.setPlanSelectedDay);

  // renderPlanner() рядок 3369: якщо день ще не обрано — сьогодні.
  useEffect(() => {
    if (!selectedDay) setPlanSelectedDay(fmtDate(new Date()));
  }, [selectedDay, setPlanSelectedDay]);

  let weekLabel = 'Цей тиждень';
  if (weekOffset === 1) weekLabel = 'Наступний';
  else if (weekOffset === -1) weekLabel = 'Минулий';
  else if (weekOffset !== 0) {
    const mon = getWeekStart(weekOffset);
    const end = new Date(mon);
    end.setDate(mon.getDate() + 6);
    weekLabel = mon.getDate() + '–' + end.getDate();
  }

  return (
    <div id="planner-sec" className="tsec active">
      <div className="week-nav">
        <button className="wn-btn" onClick={() => shiftPlanWeek(-1)}>← Попередній</button>
        <span className="wn-lbl">{weekLabel}</span>
        <button className="wn-btn" onClick={() => shiftPlanWeek(1)}>Наступний →</button>
      </div>
      <WeekGrid />
      <WeekConstructor />
      {selectedDay && <DayDetail ds={selectedDay} />}
    </div>
  );
}
