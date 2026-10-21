import { useStore } from '../../state/store';
import type { Task } from '../../types';
import { COUNTER_UNITS, taskCounterStep, taskCounterTarget } from '../../utils/taskCounter';

/** The same task progress controls in the daily overview and task details. */
export default function TaskCounterControls({ t, editTarget = false }: { t: Task; editTarget?: boolean }) {
  const changeCounter = useStore(s => s.changeCounter);
  const updateCounterTarget = useStore(s => s.updateCounterTarget);
  const unit = COUNTER_UNITS[t.counterUnit || 'count'];
  const step = taskCounterStep(t);
  const target = taskCounterTarget(t);
  const disabled = t.done || t.trashed || t.someday;
  return <div className="cw" role="group" aria-label={'Лічильник: ' + t.title}>
    <button className="cbtn" disabled={disabled || !t.counter} aria-label={'Зменшити: ' + t.title} onClick={() => changeCounter(t.id, -step)}>−{step}</button>
    <span className="cvv" aria-live="polite">{t.counter || 0} {unit}</span>
    <button className="cbtn" disabled={disabled} aria-label={'Збільшити: ' + t.title} onClick={() => changeCounter(t.id, step)}>+{step}</button>
    {editTarget ? <span className="counter-goal">/ <input key={target} className="ct-inp" type="number" aria-label={'Ціль: ' + t.title} defaultValue={target} min={0} onBlur={e => updateCounterTarget(t.id, Number(e.target.value))} /> {unit}</span>
      : target > 0 && <span className="counter-goal">Ціль: {target} {unit}</span>}
  </div>;
}
