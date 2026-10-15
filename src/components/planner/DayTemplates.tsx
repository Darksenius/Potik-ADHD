import { useStore } from '../../state/store';
import type { DateKey } from '../../types';
import Icon from '../common/Icon';

/** Apply-only preset picker for the selected day in План. */
export default function DayTemplates({ ds }: { ds: DateKey }) {
  const dayTemplates = useStore((s) => s.dayTemplates);
  const applyDayTemplate = useStore((s) => s.applyDayTemplate);

  return (
    <div style={{ margin: '4px 0 8px' }}>
      <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--t3)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 5 }}>
        <Icon name="archive" /> Пресети дня
      </div>
      {dayTemplates.length ? (
        <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap', marginBottom: 5 }}>
          {dayTemplates.map((tp) => (
            <button
              className="ptype"
              type="button"
              key={tp.id}
              onClick={() => applyDayTemplate(ds, tp.id)}
              title="Застосувати часові зони й додати типові задачі до дня"
              aria-label={`Застосувати пресет «${tp.name}» до дня`}
            >
              {tp.name} · {(tp.blocks || []).length} зон · {(tp.tasks || []).length} задач
            </button>
          ))}
        </div>
      ) : (
        <div style={{ fontSize: 10, color: 'var(--t3)', marginBottom: 5 }}>
          Пресетів ще немає. Створи їх у «Ще» → «Пресети дня».
        </div>
      )}
    </div>
  );
}
