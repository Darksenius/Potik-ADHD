import { useState } from 'react';
import { useStore } from '../../state/store';
import type { Task, TaskType } from '../../types';
import { fmtDate, WD } from '../../utils/date';
import { saveState, saveIsBlocked, getSaveErrorMessage, showRecoveryBanner } from '../../services/persistence';
import { repeatDueOn } from '../../utils/taskSchedule';
import { hasTaskCounter } from '../../utils/taskCounter';
import taskTypeData from '../../content/task-types.json' with { type: 'json' };

type TaskTypeInfo = { label: string; icon: string; xp: number; hint: string; description: string; example: string };
const TYPE_CATALOG = taskTypeData as Record<TaskType, TaskTypeInfo>;
const TYPE_OPTIONS: { value: TaskType; label: string }[] = (Object.keys(TYPE_CATALOG) as TaskType[])
  .map(value => ({ value, label: TYPE_CATALOG[value].label }));
const TYPE_HINTS = (Object.keys(TYPE_CATALOG) as TaskType[]).reduce((hints, value) => {
  hints[value] = TYPE_CATALOG[value].hint;
  return hints;
}, {} as Record<TaskType, string>);
const REPEATS: { value: Task['repeat']; label: string }[] = [
  { value: 'none', label: 'Без повторення' }, { value: 'daily', label: 'Щодня' },
  { value: 'weekly', label: 'Щотижня' }, { value: 'weekdays', label: 'Понеділок — п’ятниця' },
  { value: 'weekend', label: 'Субота й неділя' }, { value: 'custom', label: 'Обрані дні тижня' },
  { value: 'interval', label: 'Через інтервал після виконання' }, { value: 'everyzone', label: 'Кожна зона' },
];

export default function TaskEditor() {
  const editorTaskId = useStore(s => s.editorTaskId);
  const initialDate = useStore(s => s.editorInitialDate);
  const tasks = useStore(s => s.tasks);
  const zones = useStore(s => s.zones);
  const folders = useStore(s => s.folders);
  const saveTask = useStore(s => s.saveTask);
  const snoozeTask = useStore(s => s.snoozeTask);
  const addFolder = useStore(s => s.addFolder);
  const closeTaskEditor = useStore(s => s.closeTaskEditor);
  const isNew = editorTaskId === 'new';
  const existing = !isNew ? tasks.find(t => t.id === editorTaskId) : undefined;
  const [title, setTitle] = useState(existing?.title || '');
  const [type, setType] = useState<TaskType>(existing?.type || 'simple');
  const [zoneId, setZoneId] = useState(existing?.zoneId ? String(existing.zoneId) : '');
  const [folderId, setFolderId] = useState(existing?.folderId || '');
  const [repeat, setRepeat] = useState<Task['repeat']>(existing?.repeat || 'none');
  const [repeatDays, setRepeatDays] = useState(existing?.repeatDays?.slice() || Array<boolean>(7).fill(false));
  const [repeatInterval, setRepeatInterval] = useState(existing?.repeatInterval || 30);
  const [repeatUnit, setRepeatUnit] = useState(existing?.repeatUnit || 'min');
  const [tagsText, setTagsText] = useState((existing?.tags || []).join(', '));
  const [date, setDate] = useState((existing?.type === 'sched' ? existing.schedDate || existing.planDate : existing?.planDate) || initialDate || '');
  const [time, setTime] = useState(existing?.schedTime || '');
  const [alarmTime, setAlarmTime] = useState(existing?.alarmTime || '');
  const [winStart, setWinStart] = useState(existing?.windowStart || '07:00');
  const [winEnd, setWinEnd] = useState(existing?.windowEnd || '09:00');
  const [note, setNote] = useState(existing?.note || '');
  const [checklistDraft, setChecklistDraft] = useState('');
  const [cntTgt, setCntTgt] = useState(existing?.counterTarget ?? (existing?.type === 'counter' ? 10 : 0));
  const [counterEnabled, setCounterEnabled] = useState(existing ? hasTaskCounter(existing) : false);
  const [counterStep, setCounterStep] = useState(existing?.counterStep || 1);
  const [counterUnit, setCounterUnit] = useState<Task['counterUnit']>(existing?.counterUnit || 'count');
  const [negXp, setNegXp] = useState(existing?.negXp || 5);
  const [reminder, setReminder] = useState(existing?.reminderEnabled !== false);
  const [advance, setAdvance] = useState(String(existing?.remindBeforeMinutes || 0));
  const [error, setError] = useState('');
  const [keepPast, setKeepPast] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [savedNewId, setSavedNewId] = useState<number | null>(null);
  const scheduled = type === 'sched';
  const special = type !== 'simple' && type !== 'sched';
  const today = fmtDate(new Date());
  const tomorrow = new Date(); tomorrow.setDate(tomorrow.getDate() + 1);
  const pastTime = scheduled && date && time && new Date(date + 'T' + time).getTime() < Date.now();
  const dateLabel = date ? new Date(date + 'T12:00:00').toLocaleDateString('uk', { day: 'numeric', month: 'long', year: 'numeric' }) : '';
  if (editorTaskId == null || (!isNew && !existing)) return null;

  function chooseType(value: TaskType) {
    setType(value); setDirty(true); setError('');
    if (value === 'counter') { setCounterEnabled(true); if (!cntTgt) setCntTgt(10); }
    if (value === 'sched' && !date) setDate(today);
  }
  function close() {
    if (!dirty || window.confirm('Залишити редактор без збереження змін?')) closeTaskEditor();
  }
  function submit(snooze?: number | 'tomorrow') {
    if (!title.trim()) { setError('Напиши, що потрібно зробити.'); return; }
    if (scheduled && (!date || !time)) { setError('Вибери дату й час запланованої задачі.'); return; }
    if (type === 'alarm' && !alarmTime) { setError('Вибери час будильника.'); return; }
    if (repeat === 'custom' && !repeatDays.some(Boolean)) { setError('Вибери хоча б один день повторення.'); return; }
    if (date && ['weekdays', 'weekend', 'custom'].includes(repeat)
      && !repeatDueOn({ repeat, repeatDays } as Task, new Date(date + 'T12:00:00'))) {
      setError('Вибрана дата не входить у дні повторення. Зміни першу дату або дні повторення.'); return;
    }
    if (type === 'zonelinked' && !zoneId) { setError('Вибери зону для цієї задачі.'); return; }
    if (repeat === 'everyzone' && !zoneId) { setError('Для повторення при вході в зону вибери зону.'); return; }
    if ((counterEnabled || type === 'counter') && type !== 'negative' && (!Number.isSafeInteger(counterStep) || counterStep <= 0 || !Number.isSafeInteger(cntTgt) || cntTgt < 0)) {
      setError('Введи цілий крок, більший за 0, і цілу ціль — 0 або більше.'); return;
    }
    if (pastTime && !keepPast && snooze === undefined) { setError('Цей час уже минув. Залиши його свідомо або зміни дату чи час.'); return; }
    const patch: Partial<Task> & { title: string; type: TaskType } = {
      title: title.trim(), type, zoneId: zoneId ? Number(zoneId) : null, folderId: folderId || null,
      repeat, repeatDays, repeatInterval, repeatUnit, tags: tagsText.split(',').map(t => t.trim()).filter(Boolean),
      planDate: date || undefined, alarmTime, schedDate: scheduled ? date : '', schedTime: scheduled ? time : '',
      windowStart: winStart, windowEnd: winEnd, note, counterTarget: cntTgt, negXp,
      counterEnabled, counterStep, counterUnit,
      reminderEnabled: reminder, remindBeforeMinutes: Number(advance),
      ...(isNew && type === 'check' ? { items: checklistDraft.split('\n').map(text => text.trim()).filter(Boolean).map(text => ({ text, done: false })) } : {}),
    };
    if (saveIsBlocked() || useStore.getState().backupBanner) {
      saveState(); setError(getSaveErrorMessage()); return;
    }
    const retainedId = savedNewId !== null && tasks.some(t => t.id === savedNewId) ? savedNewId : null;
    const id = saveTask(isNew ? retainedId : existing!.id, patch);
    if (isNew) setSavedNewId(id);
    if (snooze !== undefined) snoozeTask(id, snooze);
    if (!saveState()) { setError(getSaveErrorMessage()); return; }
    closeTaskEditor();
    useStore.getState().showToast(snooze === undefined ? 'Задачу збережено' : 'Зміни збережено, задачу перенесено');
  }

  return <div id="edit-page" className="open" role="dialog" aria-modal="true" aria-labelledby="task-editor-title">
    <div id="edit-header"><button className="edit-back" onClick={close}>← Назад</button><h2 id="task-editor-title">{isNew ? 'Нова задача' : 'Редагування задачі'}</h2></div>
    <form id="edit-body" onChange={() => { setDirty(true); setError(''); }} onSubmit={e => { e.preventDefault(); submit(); }}>
      <div className="ef"><label className="el" htmlFor="task-title">Що зробити?</label><input id="task-title" className="ei" value={title} onChange={e => setTitle(e.target.value)} placeholder="Наприклад, забрати посилку" autoFocus /></div>
      <div className="editor-kinds" aria-label="Основний тип задачі">
        <button type="button" aria-pressed={type === 'simple'} onClick={() => chooseType('simple')}>Проста<span>Без обов’язкового часу</span></button>
        <button type="button" aria-pressed={scheduled} onClick={() => chooseType('sched')}>Запланована<span>На точну дату й час</span></button>
      </div>
      <div className="ef">
        <label className="el" htmlFor="task-type">Тип задачі</label>
        <select id="task-type" className="ei" value={type} onChange={e => chooseType(e.target.value as TaskType)} aria-describedby="task-type-hint">
          {TYPE_OPTIONS.map(o => <option value={o.value} key={o.value}>{o.label}</option>)}
        </select>
        <p className="editor-hint">Усі {TYPE_OPTIONS.length} типів доступні тут.</p>
        <p id="task-type-hint" className="editor-hint">{TYPE_HINTS[type]}</p>
      </div>
      {isNew && type === 'check' && <label className="el" htmlFor="task-checklist">Кроки — кожен із нового рядка<textarea id="task-checklist" className="ei" rows={4} value={checklistDraft} onChange={e => setChecklistDraft(e.target.value)} placeholder={'Зібрати речі\nПеревірити документи\nВийти з дому'} /></label>}
      <details id="task-note-section" className="editor-section" open={type === 'note' || !!note || undefined}>
        <summary>Нотатка (необов’язково)</summary>
        <div className="ef"><label className="el" htmlFor="task-note">Нотатка до задачі</label><textarea id="task-note" className="ei" rows={4} value={note} onChange={e => setNote(e.target.value)} placeholder="Деталі, адреса чи те, що допоможе виконати задачу" /></div>
      </details>
      <section className="editor-section">
        <h3>Коли?</h3>
        <div className="editor-actions">
          {!scheduled && <button type="button" onClick={() => { setDate(''); setDirty(true); }}>Без дати</button>}
          <button type="button" onClick={() => { setDate(today); setKeepPast(false); setDirty(true); }}>Сьогодні</button>
          <button type="button" onClick={() => { setDate(fmtDate(tomorrow)); setKeepPast(false); setDirty(true); }}>Завтра</button>
        </div>
        <div className="editor-row"><div className="ef"><label className="el" htmlFor="task-date">Дата{scheduled ? ' *' : ''}</label><input id="task-date" className="ei" type="date" value={date} onChange={e => { setDate(e.target.value); setKeepPast(false); }} /></div>
          {scheduled && <div className="ef"><label className="el" htmlFor="task-time">Час *</label><input id="task-time" className="ei" type="time" value={time} onChange={e => { setTime(e.target.value); setKeepPast(false); }} /></div>}
        </div>
        {type === 'alarm' && <label className="el" htmlFor="task-alarm-time">Час будильника<input id="task-alarm-time" className="ei" type="time" value={alarmTime} onChange={e => setAlarmTime(e.target.value)} /></label>}
        {type === 'timewin' && <div className="editor-row"><label className="el" htmlFor="task-window-start">Початок вікна<input id="task-window-start" className="ei" type="time" value={winStart} onChange={e => setWinStart(e.target.value)} /></label><label className="el" htmlFor="task-window-end">Кінець вікна<input id="task-window-end" className="ei" type="time" value={winEnd} onChange={e => setWinEnd(e.target.value)} /></label></div>}
        {pastTime && <div className="editor-warning"><p>Вибраний час уже минув. Задача залишиться в цьому дні.</p><label><input type="checkbox" checked={keepPast} onChange={e => setKeepPast(e.target.checked)} /> Зберегти з минулим часом</label></div>}
        {(scheduled || type === 'alarm') && <div className="ef"><label className="editor-check"><input type="checkbox" checked={reminder} onChange={e => setReminder(e.target.checked)} /> Нагадати у заданий час</label>
          {scheduled && reminder && <label className="el">Додатково заздалегідь<select className="ei" value={advance} onChange={e => setAdvance(e.target.value)}>
            <option value="0">Без додаткового нагадування</option><option value="5">За 5 хвилин</option><option value="15">За 15 хвилин</option><option value="60">За годину</option><option value="1440">За добу</option>
          </select></label>}
        </div>}
        <div className="ef"><label className="el" htmlFor="task-zone">У якій зоні?</label><select id="task-zone" className="ei" value={zoneId} onChange={e => setZoneId(e.target.value)}><option value="">Будь-яка зона</option>{zoneId && !zones.some(z => String(z.id) === zoneId) && <option value={zoneId}>Зону видалено — вибери іншу</option>}{zones.map(z => <option value={z.id} key={z.id}>{z.nm}</option>)}</select><p className="editor-hint">Наприклад, «Підмести підлогу» у Вечорі. В інший час задача є в Плані та всіх задачах.</p></div>
      </section>
      <details className="editor-section" open={special || counterEnabled || undefined}>
        <summary>Додаткові можливості{special ? ' · ' + TYPE_OPTIONS.find(o => o.value === type)?.label : ''}</summary>
        <div className="ef"><label className="el" htmlFor="task-repeat">Повторення</label><select id="task-repeat" className="ei" value={repeat} onChange={e => setRepeat(e.target.value as Task['repeat'])}>{REPEATS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}</select></div>
        {repeat === 'interval' && <div className="editor-row"><label className="el">Інтервал<input type="number" className="ei" min="1" value={repeatInterval} onChange={e => setRepeatInterval(Math.max(1, Number(e.target.value)))} /></label><label className="el">Одиниця<select className="ei" value={repeatUnit} onChange={e => setRepeatUnit(e.target.value as typeof repeatUnit)}><option value="sec">секунд</option><option value="min">хвилин</option><option value="hour">годин</option><option value="day">днів</option><option value="week">тижнів</option><option value="month">місяців (30 днів)</option></select></label></div>}
        {repeat === 'custom' && <div className="rep-days">{WD.map((day, i) => <button className={'rd' + (repeatDays[i] ? ' on' : '')} type="button" aria-pressed={repeatDays[i]} key={day} onClick={() => { setRepeatDays(days => days.map((v, j) => j === i ? !v : v)); setDirty(true); }}>{day}</button>)}</div>}
        {type !== 'negative' && <label className="editor-check"><input id="task-counter-enabled" type="checkbox" checked={type === 'counter' || counterEnabled} disabled={type === 'counter'} onChange={e => setCounterEnabled(e.target.checked)} /> Лічильник прогресу</label>}
        {type !== 'negative' && (type === 'counter' || counterEnabled) && <>
          <div className="editor-row"><label className="el" htmlFor="task-counter-step">Крок + / −<input id="task-counter-step" type="number" className="ei" min="1" value={counterStep} onChange={e => setCounterStep(Number(e.target.value))} /></label>
            <label className="el" htmlFor="task-counter-unit">Одиниця<select id="task-counter-unit" className="ei" value={counterUnit} onChange={e => setCounterUnit(e.target.value as Task['counterUnit'])}><option value="count">раз</option><option value="ml">мл</option><option value="min">хв</option></select></label></div>
          <label className="el" htmlFor="task-counter-target">Ціль лічильника (0 — без цілі)<input id="task-counter-target" type="number" className="ei" min="0" value={cntTgt} onChange={e => setCntTgt(Number(e.target.value))} /></label>
          <p className="editor-hint">Наприклад, «Розтяжка»: крок 5 хв. Ціль обираєш сам; досягнення цілі не завершує задачу автоматично.</p>
        </>}
        {type === 'negative' && <label className="el">Штраф досвіду<input type="number" className="ei" min="1" value={negXp} onChange={e => setNegXp(Math.max(1, Number(e.target.value)))} /></label>}
        <div className="ef"><label className="el" htmlFor="task-folder">Папка</label><div className="editor-row"><select id="task-folder" className="ei" value={folderId} onChange={e => setFolderId(e.target.value)}><option value="">Без папки</option>{folders.map(f => <option value={f.id} key={f.id}>{f.ico} {f.nm}</option>)}</select><button type="button" onClick={() => { const name = window.prompt('Назва нової папки:'); if (name?.trim()) { const id = addFolder(name.trim(), '📁'); if (id) { setFolderId(id); setDirty(true); } } }}>+ Папка</button></div></div>
        <div className="ef"><label className="el" htmlFor="task-tags">Теги через кому</label><input id="task-tags" className="ei" value={tagsText} onChange={e => setTagsText(e.target.value)} /></div>
        {!isNew && <div className="ef"><span className="el">Зберегти зміни й перенести</span><div className="editor-actions">{[15, 60, 180].map(min => <button key={min} type="button" onClick={() => submit(min)}>+{min < 60 ? min + ' хв' : min / 60 + ' год'}</button>)}<button type="button" onClick={() => submit('tomorrow')}>На завтра</button></div></div>}
      </details>
      <div className="schedule-preview" aria-live="polite">
        {date ? <>У плані: <strong>{dateLabel}{scheduled && time ? ', ' + time : ''}</strong>. </> : <>Без дати — доступна, коли підходять зона й умови задачі. </>}
        {zoneId && <>У «Зараз» лише в зоні <strong>{zones.find(z => String(z.id) === zoneId)?.nm || 'яку видалено'}</strong>. </>}
        {scheduled && date && <>У «Зараз» — після заданого часу. До нього — в Плані та всіх задачах. </>}
        {type === 'simple' && <>Без сповіщення. Для сигналу на точний час вибери «Запланована». </>}
        {(scheduled || type === 'alarm') && (reminder ? <>Нагадаю {scheduled ? time || 'після вибору часу' : alarmTime || 'після вибору часу'}{advance !== '0' && scheduled ? ' і за ' + advance + ' хв' : ''}. {zoneId && <>Сигнал іде за заданим часом; зона визначає показ у «Зараз». </>}</> : <>Без сповіщення. </>)}
        {repeat !== 'none' && <>{REPEATS.find(r => r.value === repeat)?.label}.</>}
      </div>
      {error && <div className="editor-error" role="alert"><p>{error}</p>{saveIsBlocked() && <button type="button" onClick={showRecoveryBanner}>Відкрити відновлення</button>}</div>}
      <button id="edit-save-fab" type="submit">{isNew ? 'Створити задачу' : 'Зберегти зміни'}</button>
    </form>
  </div>;
}
