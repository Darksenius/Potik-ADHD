import { useStore } from '../../state/store';
import type { Task } from '../../types';
import { WD } from '../../utils/date';
import { REPEAT_LABELS, CONTEXT_TAGS } from '../../constants';


export default function TaskBody({ t }: { t: Task }) {
  const toggleChecklistItem = useStore((s) => s.toggleChecklistItem);
  const addChecklistItem = useStore((s) => s.addChecklistItem);
  const changeCounter = useStore((s) => s.changeCounter);
  const updateCounterTarget = useStore((s) => s.updateCounterTarget);
  const updateNote = useStore((s) => s.updateNote);
  const updateAlarm = useStore((s) => s.updateAlarm);
  const toggleCtxTag = useStore((s) => s.toggleCtxTag);
  const toggleTask = useStore((s) => s.toggleTask);
  const removeTag = useStore((s) => s.removeTag);
  const requestEditTask = useStore((s) => s.requestEditTask);

  return (
    <>
      {t.type === 'check' && (
        <>
          <div className="bl">Пункти</div>
          {(t.items || []).map((it, i) => (
            <div className={'ci' + (it.done ? ' cid' : '')} key={i}>
              <input type="checkbox" checked={it.done} onChange={() => toggleChecklistItem(t.id, i)} />
              <span>{it.text}</span>
            </div>
          ))}
          <ChecklistAdd taskId={t.id} onAdd={addChecklistItem} />
        </>
      )}

      {t.type === 'counter' && (
        <>
          <div className="bl">Лічильник</div>
          <div className="cw">
            <button className="cbtn" onClick={() => changeCounter(t.id, -1)}>−</button>
            <span className="cvv">{t.counter}</span>
            <button className="cbtn" onClick={() => changeCounter(t.id, 1)}>+</button>
            <span style={{ fontSize: 11, color: 'var(--t3)' }}>
              /{' '}
              <input
                className="ct-inp"
                type="number"
                defaultValue={t.counterTarget || 10}
                min={1}
                onBlur={(e) => updateCounterTarget(t.id, parseInt(e.target.value) || 10)}
              />
            </span>
          </div>
        </>
      )}

      {t.type === 'note' && (
        <>
          <div className="bl">Нотатка</div>
          <textarea className="tnote" defaultValue={t.note || ''} onInput={(e) => updateNote(t.id, e.currentTarget.value)} />
        </>
      )}

      {t.type === 'alarm' && (
        <>
          <div className="bl">Час будильника</div>
          <div className="aw">
            <input type="time" defaultValue={t.alarmTime || ''} onChange={(e) => updateAlarm(t.id, e.target.value)} />
            <span className="aw-st">{t.alarmFired ? '✓ спрацював' : t.alarmTime ? '⏳ очікує' : '— не задано'}</span>
          </div>
        </>
      )}

      {t.type === 'ctx' && (
        <>
          <div className="bl">Контексти</div>
          <div className="ctx-tags">
            {CONTEXT_TAGS.map((tg) => (
              <div className={'ctx-tag' + ((t.ctxTags || []).includes(tg) ? ' sel' : '')} key={tg} onClick={() => toggleCtxTag(t.id, tg)}>
                {tg}
              </div>
            ))}
          </div>
        </>
      )}

      {t.type === 'negative' && (
        <>
          <div className="bl">Зафіксовано</div>
          <div className="neg-counter">
            <div className="neg-cnt-v">{t.counter || 0}</div>
            <span style={{ fontSize: 11, color: 'var(--t3)' }}>раз сьогодні</span>
            <button className="neg-tap-btn" onClick={() => toggleTask(t.id)}>⚠ Зафіксувати (−{t.negXp} досвіду)</button>
          </div>
        </>
      )}

      {t.type === 'zonelinked' && (
        <>
          <div className="bl">Прив&apos;язана до зони</div>
          <div style={{ fontSize: 12, color: 'var(--t2)', marginTop: 4 }}>
            Активується при вході в зону <b style={{ color: t.zoneColor || '#888' }}>{t.zoneName}</b>. Повторення: {REPEAT_LABELS[t.repeat] || '—'}.
          </div>
        </>
      )}

      {!!(t.tags || []).length && (
        <>
          <div className="bl">Теги</div>
          <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginTop: 3 }}>
            {t.tags.map((tg, i) => (
              <span
                key={i}
                style={{ background: 'color-mix(in srgb,var(--z) 10%,transparent)', border: '1px solid color-mix(in srgb,var(--z) 25%,transparent)', borderRadius: 9, padding: '2px 7px', fontSize: 10, color: 'var(--z)', display: 'inline-flex', alignItems: 'center', gap: 3 }}
              >
                #{tg} <span style={{ cursor: 'pointer', color: 'var(--t3)' }} onClick={() => removeTag(t.id, i)}>✕</span>
              </span>
            ))}
          </div>
        </>
      )}

      {t.type !== 'negative' && t.type !== 'zonelinked' && (
        <>
          <div className="bl">Повторення</div>
          <div className="rep-row">
            <span>{REPEAT_LABELS[t.repeat] || 'Без повторення'}{t.repeat === 'custom' ? ': ' + WD.filter((_, i) => t.repeatDays?.[i]).join(', ') : ''}</span>
            <button className="ab" onClick={() => requestEditTask(t.id)}>Змінити розклад</button>
          </div>
        </>
      )}
    </>
  );
}

function ChecklistAdd({ taskId, onAdd }: { taskId: number; onAdd: (id: number, text: string) => void }) {
  return (
    <div className="ci-add">
      <input
        type="text"
        placeholder="Новий пункт..."
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            const input = e.currentTarget;
            onAdd(taskId, input.value);
            input.value = '';
          }
        }}
      />
      <button
        onClick={(e) => {
          const input = e.currentTarget.previousElementSibling as HTMLInputElement;
          onAdd(taskId, input.value);
          input.value = '';
        }}
      >
        +
      </button>
    </div>
  );
}
