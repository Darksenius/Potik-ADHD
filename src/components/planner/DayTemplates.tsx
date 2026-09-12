import { useStore } from '../../state/store';
import type { DateKey } from '../../types';

export default function DayTemplates({ ds }: { ds: DateKey }) {
  const dayTemplates = useStore((s) => s.dayTemplates);
  const applyDayTemplate = useStore((s) => s.applyDayTemplate);
  const deleteDayTemplate = useStore((s) => s.deleteDayTemplate);
  const saveDayTemplate = useStore((s) => s.saveDayTemplate);

  return (
    <div style={{ margin: '4px 0 8px' }}>
      <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--t3)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 5 }}>
        📋 Шаблони дня
      </div>
      {dayTemplates.length ? (
        <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap', marginBottom: 5 }}>
          {dayTemplates.map((tp) => (
            <span className="ptype" key={tp.id} onClick={() => applyDayTemplate(ds, tp.id)} title="Застосувати зони цього шаблону до дня">
              {tp.name} ({(tp.blocks || []).length}){' '}
              <span
                onClick={(e) => {
                  e.stopPropagation();
                  deleteDayTemplate(tp.id);
                }}
                style={{ color: 'var(--t3)', cursor: 'pointer', padding: '0 3px' }}
              >
                ✕
              </span>
            </span>
          ))}
        </div>
      ) : (
        <div style={{ fontSize: 10, color: 'var(--t3)', marginBottom: 5 }}>Ще немає шаблонів — додай зони дня і збережи</div>
      )}
      <button
        className="pdd-add"
        onClick={() => {
          const nm = window.prompt('Назва шаблону:');
          if (nm && nm.trim()) saveDayTemplate(ds, nm.trim());
        }}
      >
        💾 Зберегти зони як шаблон
      </button>
    </div>
  );
}
