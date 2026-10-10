import { useStore } from '../../state/store';
import { getWeekStart } from '../../state/slices/plannerSlice';
import { fmtDate, WD } from '../../utils/date';
import { taskOccursOn } from '../../utils/taskSchedule';

export default function WeekGrid() {
  const weekOffset = useStore((s) => s.planWeekOffset);
  const selectedDay = useStore((s) => s.planSelectedDay);
  const switchDay = useStore((s) => s.setPlanSelectedDay);
  const planItems = useStore((s) => s.planItems);
  const tasks = useStore(s => s.tasks);
  const planSchedules = useStore((s) => s.planSchedules);
  const zones = useStore((s) => s.zones);
  // Підписки нижче — щоб перемалювати grid одразу при зміні розмітки зон дня
  useStore((s) => s.planDayZones);
  useStore((s) => s.planRules);
  useStore((s) => s.dayTemplates);
  useStore((s) => s.planRestDays);
  const dayBlocks = useStore((s) => s.dayBlocks);
  const dayIsRest = useStore((s) => s.dayIsRest);

  const mon = getWeekStart(weekOffset);
  const today = fmtDate(new Date());

  const days = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(mon);
    d.setDate(mon.getDate() + i);
    const ds = fmtDate(d);
    return { i, d, ds };
  });

  return (
    <div className="plan-week">
      {days.map(({ i, d, ds }) => {
        const isToday = ds === today;
        const isWE = i >= 5;
        const isRest = dayIsRest(ds);
        const items = planItems[ds] || [];
        const hasEvents = items.some((it) => it.isEvent);
        const hasTasks = items.some((it) => !it.isEvent) || tasks.some(t => taskOccursOn(t, ds));
        const sched = planSchedules[ds] || '';
        const zc: string[] = [];
        dayBlocks(ds).forEach((b) => {
          const z = zones.find((zz) => zz.id === b.zoneId);
          if (z && zc.indexOf(z.color) < 0) zc.push(z.color);
        });

        return (
          <button
            key={ds}
            type="button"
            aria-label={d.toLocaleDateString('uk', { day: 'numeric', month: 'long', year: 'numeric' }) + (hasTasks ? ', є задачі' : '')}
            aria-pressed={selectedDay === ds}
            className={'plan-day' + (isToday ? ' today' : '') + (isWE && !isRest ? ' weekend-day' : '') + (isRest ? ' rest-day' : '') + (selectedDay === ds ? ' sel' : '')}
            onClick={() => switchDay(ds)}
          >
            <div className="pd-wd">{WD[i]}</div>
            <div className="pd-num">{d.getDate()}</div>
            {isRest ? (
              <div className="pd-rest">вих</div>
            ) : (
              <>
                {hasEvents && <div style={{ fontSize: 8, marginTop: 1 }}>🎉</div>}
                {sched && <div style={{ fontSize: 7, color: 'var(--z)', marginTop: 1 }}>{sched}</div>}
              </>
            )}
            <div className={'pd-dot' + (hasTasks ? ' has' : '')} />
            {!!zc.length && (
              <div className="pd-zstrip">
                {zc.slice(0, 4).map((c, idx) => (
                  <span key={idx} style={{ background: c }} />
                ))}
              </div>
            )}
          </button>
        );
      })}
    </div>
  );
}
