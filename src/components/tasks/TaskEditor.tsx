import { useState } from 'react';
import { useStore } from '../../state/store';
import type { Task, TaskType } from '../../types';
import { fmtDate, WD } from '../../utils/date';
import { saveState } from '../../services/persistence';
import { repeatDueOn } from '../../utils/taskSchedule';

const TYPE_OPTIONS: { value: TaskType; label: string }[] = [
  { value: 'simple', label: 'Проста' }, { value: 'sched', label: 'Запланована' },
  { value: 'check', label: 'Чекліст' }, { value: 'counter', label: 'Лічильник' },
  { value: 'note', label: 'З нотаткою' }, { value: 'alarm', label: 'Будильник' },
  { value: 'timewin', label: 'Вікно часу' },
  { value: 'ctx', label: 'Контекст' }, { value: 'negative', label: 'Шкідлива звичка' },
  { value: 'zonelinked', label: 'До зони' },
];
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
  const [cntTgt, setCntTgt] = useState(existing?.counterTarget || 10);
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
    if (pastTime && !keepPast && snooze === undefined) { setError('Цей час уже минув. Залиши його свідомо або зміни дату чи час.'); return; }
    const patch: Partial<Task> & { title: string; type: TaskType } = {
      title: title.trim(), type, zoneId: zoneId ? Number(zoneId) : null, folderId: folderId || null,
      repeat, repeatDays, repeatInterval, repeatUnit, tags: tagsText.split(',').map(t => t.trim()).filter(Boolean),
      planDate: date || undefined, alarmTime, schedDate: scheduled ? date : '', schedTime: scheduled ? time : '',
      windowStart: winStart, windowEnd: winEnd, note, counterTarget: cntTgt, negXp,
      reminderEnabled: reminder, remindBeforeMinutes: Number(advance),
    };
    const id = saveTask(isNew ? savedNewId : existing!.id, patch);
    if (isNew) setSavedNewId(id);
    if (snooze !== undefined) snoozeTask(id, snooze);
    if (!saveState()) { setError('Зміни поки лише в пам’яті. Не закривай застосунок: звільни місце або зроби експорт.'); return; }
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
      {special && <p className="editor-hint">Особливий тип: {TYPE_OPTIONS.find(o => o.value === type)?.label}. Його параметри збережені нижче.</p>}
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
        {pastTime && <div className="editor-warning"><p>Вибраний час уже минув. Задача залишиться в цьому дні.</p><label><input type="checkbox" checked={keepPast} onChange={e => setKeepPast(e.target.checked)} /> Зберегти з минулим часом</label></div>}
        {(scheduled || type === 'alarm') && <div className="ef"><label className="editor-check"><input type="checkbox" checked={reminder} onChange={e => setReminder(e.target.checked)} /> Нагадати у заданий час</label>
          {scheduled && reminder && <label className="el">Додатково заздалегідь<select className="ei" value={advance} onChange={e => setAdvance(e.target.value)}>
            <option value="0">Без додаткового нагадування</option><option value="5">За 5 хвилин</option><option value="15">За 15 хвилин</option><option value="60">За годину</option><option value="1440">За добу</option>
          </select></label>}
        </div>}
      </section>
      <details className="editor-section" open={special || undefined}>
        <summary>Додаткові можливості{special ? ' · ' + TYPE_OPTIONS.find(o => o.value === type)?.label : ''}</summary>
        <div className="ef"><label className="el" htmlFor="task-type">Тип задачі</label><select id="task-type" className="ei" value={type} onChange={e => chooseType(e.target.value as TaskType)}>{TYPE_OPTIONS.map(o => <option value={o.value} key={o.value}>{o.label}</option>)}</select></div>
        <div className="ef"><label className="el" htmlFor="task-repeat">Повторення</label><select id="task-repeat" className="ei" value={repeat} onChange={e => setRepeat(e.target.value as Task['repeat'])}>{REPEATS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}</select></div>
        {repeat === 'interval' && <div className="editor-row"><label className="el">Інтервал<input type="number" className="ei" min="1" value={repeatInterval} onChange={e => setRepeatInterval(Math.max(1, Number(e.target.value)))} /></label><label className="el">Одиниця<select className="ei" value={repeatUnit} onChange={e => setRepeatUnit(e.target.value as typeof repeatUnit)}><option value="sec">секунд</option><option value="min">хвилин</option><option value="hour">годин</option><option value="day">днів</option><option value="week">тижнів</option><option value="month">місяців (30 днів)</option></select></label></div>}
        {repeat === 'custom' && <div className="rep-days">{WD.map((day, i) => <button className={'rd' + (repeatDays[i] ? ' on' : '')} type="button" aria-pressed={repeatDays[i]} key={day} onClick={() => { setRepeatDays(days => days.map((v, j) => j === i ? !v : v)); setDirty(true); }}>{day}</button>)}</div>}
        {type === 'alarm' && <label className="el">Час будильника<input className="ei" type="time" value={alarmTime} onChange={e => setAlarmTime(e.target.value)} /></label>}
        {type === 'timewin' && <div className="editor-row"><label className="el">Початок вікна<input className="ei" type="time" value={winStart} onChange={e => setWinStart(e.target.value)} /></label><label className="el">Кінець вікна<input className="ei" type="time" value={winEnd} onChange={e => setWinEnd(e.target.value)} /></label></div>}
        {type === 'note' && <label className="el">Нотатка<textarea className="ei" value={note} onChange={e => setNote(e.target.value)} /></label>}
        {type === 'counter' && <label className="el">Ціль лічильника<input type="number" className="ei" min="1" value={cntTgt} onChange={e => setCntTgt(Math.max(1, Number(e.target.value)))} /></label>}
        {type === 'negative' && <label className="el">Штраф досвіду<input type="number" className="ei" min="1" value={negXp} onChange={e => setNegXp(Math.max(1, Number(e.target.value)))} /></label>}
        <div className="ef"><label className="el" htmlFor="task-zone">Зона</label><select id="task-zone" className="ei" value={zoneId} onChange={e => setZoneId(e.target.value)}><option value="">Без зони</option>{zones.map(z => <option value={z.id} key={z.id}>{z.nm}</option>)}</select></div>
        <div className="ef"><label className="el" htmlFor="task-folder">Папка</label><div className="editor-row"><select id="task-folder" className="ei" value={folderId} onChange={e => setFolderId(e.target.value)}><option value="">Без папки</option>{folders.map(f => <option value={f.id} key={f.id}>{f.ico} {f.nm}</option>)}</select><button type="button" onClick={() => { const name = window.prompt('Назва нової папки:'); if (name?.trim()) { const id = addFolder(name.trim(), '📁'); if (id) { setFolderId(id); setDirty(true); } } }}>+ Папка</button></div></div>
        <div className="ef"><label className="el" htmlFor="task-tags">Теги через кому</label><input id="task-tags" className="ei" value={tagsText} onChange={e => setTagsText(e.target.value)} /></div>
        {!isNew && <div className="ef"><span className="el">Зберегти зміни й перенести</span><div className="editor-actions">{[15, 60, 180].map(min => <button key={min} type="button" onClick={() => submit(min)}>+{min < 60 ? min + ' хв' : min / 60 + ' год'}</button>)}<button type="button" onClick={() => submit('tomorrow')}>На завтра</button></div></div>}
      </details>
      <div className="schedule-preview" aria-live="polite">
        {date ? <>У плані: <strong>{dateLabel}{scheduled && time ? ', ' + time : ''}</strong>. </> : type === 'simple' && repeat === 'none' && !zoneId ? <>Без дати — у Вхідних. Вибери день, щоб додати у план. </> : <>Показ за додатковими умовами задачі. </>}
        {scheduled && date && <>У списку на сьогодні — від початку вибраного дня, щоб можна було підготуватися. </>}
        {type === 'simple' && <>Без сповіщення. Для сигналу на точний час вибери «Запланована». </>}
        {(scheduled || type === 'alarm') && (reminder ? <>Нагадаю {scheduled ? time || 'після вибору часу' : alarmTime || 'після вибору часу'}{advance !== '0' && scheduled ? ' і за ' + advance + ' хв' : ''}. </> : <>Без сповіщення. </>)}
        {repeat !== 'none' && <>{REPEATS.find(r => r.value === repeat)?.label}.</>}
      </div>
      {error && <p className="editor-error" role="alert">{error}</p>}
      <button id="edit-save-fab" type="submit">{isNew ? 'Створити задачу' : 'Зберегти зміни'}</button>
    </form>
  </div>;
}
