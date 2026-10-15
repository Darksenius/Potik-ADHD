import { useStore } from '../../state/store';
import type { Task } from '../../types';
import { WD } from '../../utils/date';
import { REPEAT_LABELS, CONTEXT_TAGS } from '../../constants';
import { COUNTER_UNITS, hasTaskCounter, taskCounterStep, taskCounterTarget } from '../../utils/taskCounter';
import Icon from '../common/Icon';


export default function TaskBody({ t }: { t: Task }) {
  const toggleChecklistItem = useStore((s) => s.toggleChecklistItem);
  const addChecklistItem = useStore((s) => s.addChecklistItem);
  const editChecklistItem = useStore((s) => s.editChecklistItem);
  const deleteChecklistItem = useStore((s) => s.deleteChecklistItem);
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
              <input type="checkbox" aria-label={it.text} checked={it.done} disabled={t.done} onChange={() => toggleChecklistItem(t.id, i)} />
              <input key={it.text} className="ei" aria-label={'Текст пункту ' + (i + 1)} defaultValue={it.text} onBlur={e => { editChecklistItem(t.id, i, e.target.value); e.target.value = e.target.value.trim() || it.text; }} />
              <button className="ab" aria-label={'Видалити пункт: ' + it.text} onClick={() => deleteChecklistItem(t.id, i)}><Icon name="close" /></button>
            </div>
          ))}
          <ChecklistAdd taskId={t.id} onAdd={addChecklistItem} autoFocus={!t.items?.length && Date.now() - new Date(t.created).getTime() < 5000} />
          <p className="editor-hint">Галочки позначають пункти. Заверши всю задачу кнопкою «Виконати».</p>
        </>
      )}

      {hasTaskCounter(t) && (
        <>
          <div className="bl">Лічильник</div>
          <div className="cw">
            <button className="cbtn" disabled={t.done || !t.counter} aria-label={'Зменшити: ' + t.title} onClick={() => changeCounter(t.id, -taskCounterStep(t))}>−{taskCounterStep(t)}</button>
            <span className="cvv">{t.counter || 0} {COUNTER_UNITS[t.counterUnit || 'count']}</span>
            <button className="cbtn" disabled={t.done} aria-label={'Збільшити: ' + t.title} onClick={() => changeCounter(t.id, taskCounterStep(t))}>+{taskCounterStep(t)}</button>
            <span style={{ fontSize: 11, color: 'var(--t3)' }}>
              /{' '}
              <input
                className="ct-inp"
                type="number"
                aria-label={'Ціль: ' + t.title}
                defaultValue={taskCounterTarget(t)}
                min={0}
                onBlur={(e) => updateCounterTarget(t.id, Number(e.target.value))}
              />
            </span>
          </div>
          <p className="editor-hint">{taskCounterTarget(t) === 0 ? 'Без цілі. ' : t.cntHit ? 'Цілі досягнуто. ' : ''}0 у полі цілі — без цілі. Завершення задачі — окрема кнопка «Виконати».</p>
        </>
      )}

      {(t.type === 'note' || typeof t.note === 'string') && (
        <>
          <label className="bl" htmlFor={'task-note-' + t.id}>Нотатка до задачі</label>
          <textarea id={'task-note-' + t.id} className="tnote" value={t.note || ''} onChange={(e) => updateNote(t.id, e.currentTarget.value)} />
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
          <div className="bl">Ручні мітки</div>
          <div className="ctx-tags">
            {CONTEXT_TAGS.map((tg) => (
              <button type="button" aria-pressed={(t.ctxTags || []).includes(tg)} className={'ctx-tag' + ((t.ctxTags || []).includes(tg) ? ' sel' : '')} key={tg} onClick={() => toggleCtxTag(t.id, tg)}>
                {tg}
              </button>
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
            <button className="neg-tap-btn" onClick={() => toggleTask(t.id)}><Icon name="note" /> Зафіксувати (−{t.negXp} досвіду)</button>
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

function ChecklistAdd({ taskId, onAdd, autoFocus }: { taskId: number; onAdd: (id: number, text: string) => void; autoFocus?: boolean }) {
  return (
    <div className="ci-add">
      <input
        type="text"
        autoFocus={autoFocus}
        aria-label="Новий пункт чекліста"
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
