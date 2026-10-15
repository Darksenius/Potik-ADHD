import { useEffect, useState, type FormEvent } from 'react';
import { useStore } from '../../state/store';
import type { DateKey, DayTemplate, DayTemplateBlock, DayTemplateTask, TaskType } from '../../types';
import { fmtDate, fmtHuman, isDateKey, normalizeDayRange, normalizeTime } from '../../utils/date';
import { captureDayPresetTask } from '../../utils/dayPresetTasks';
import taskTypeCatalog from '../../content/task-types.json' with { type: 'json' };
import Icon from '../common/Icon';
import WeekConstructor from './WeekConstructor';
import './day-presets.css';

type Draft = Omit<DayTemplate, 'id'> & { id?: string };

function blankDraft(): Draft {
  return { name: '', blocks: [], tasks: [] };
}

function newEntryId(): string {
  return `entry-${globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`}`;
}

function taskValidationError(entry: DayTemplateTask, zones: { id: number }[]): string {
  const task = entry.task;
  if (!task.title.trim()) return 'Введи назву задачі.';
  if (task.type === 'zonelinked' && !zones.some((zone) => zone.id !== 0 && zone.id === task.zoneId)) return 'Для задачі «У зоні» обери наявну зону.';
  if (task.type === 'sched' && !normalizeTime(task.schedTime)) return 'Вкажи коректний час задачі.';
  if (task.type === 'alarm' && !normalizeTime(task.alarmTime)) return 'Вкажи коректний час сигналу.';
  if (task.type === 'timewin' && (!normalizeTime(task.windowStart) || !normalizeTime(task.windowEnd))) return 'Вкажи початок і кінець вікна часу.';
  if (task.type === 'counter' || task.type === 'negative') {
    const target = task.counterTarget ?? (task.type === 'counter' ? 10 : 0);
    const step = task.counterStep ?? 1;
    if (!Number.isSafeInteger(target) || target < 0) return 'Ціль має бути цілим числом від 0.';
    if (!Number.isSafeInteger(step) || step < 1) return 'Крок має бути цілим числом від 1.';
  }
  if (task.type === 'negative' && (!Number.isSafeInteger(task.negXp ?? 5) || (task.negXp ?? 5) < 0)) return 'Мінус досвіду має бути цілим числом від 0.';
  return '';
}

function blockValidationError(block: DayTemplateBlock, zones: { id: number }[]): string {
  if (!zones.some((zone) => zone.id === block.zoneId)) return 'Обери наявну зону.';
  if (!normalizeDayRange(block.s, block.e)) return 'Вкажи коректний час; кінець має бути пізнішим за початок. Для кінця дня можна вказати 24:00.';
  return '';
}

export default function DayPresetsTab() {
  const planSelectedDay = useStore((s) => s.planSelectedDay);
  const zones = useStore((s) => s.zones);
  const dayTemplates = useStore((s) => s.dayTemplates);
  const saveDayTemplate = useStore((s) => s.saveDayTemplate);
  const deleteDayTemplate = useStore((s) => s.deleteDayTemplate);

  const [sourceDate, setSourceDate] = useState<DateKey>(() => planSelectedDay || fmtDate(new Date()));
  const [draft, setDraft] = useState<Draft>(blankDraft);
  const [savedMessage, setSavedMessage] = useState('');
  const validDate = isDateKey(sourceDate);

  useEffect(() => {
    if (planSelectedDay) setSourceDate(planSelectedDay);
  }, [planSelectedDay]);

  const setBlock = (index: number, patch: Partial<DayTemplateBlock>) => {
    setDraft((current) => ({
      ...current,
      blocks: current.blocks.map((block, i) => i === index ? { ...block, ...patch } : block),
    }));
    setSavedMessage('');
  };

  const setPresetTask = (index: number, patch: Partial<DayTemplateTask['task']>) => {
    setDraft((current) => ({
      ...current,
      tasks: (current.tasks || []).map((entry, i) => i === index
        ? { ...entry, task: { ...entry.task, ...patch } }
        : entry),
    }));
    setSavedMessage('');
  };

  const loadSourceDay = () => {
    if (!validDate) return;
    const state = useStore.getState();
    const blocks = state.dayBlocks(sourceDate).map((block) => ({ zoneId: block.zoneId, s: block.s, e: block.e }));
    const zoneIds = new Set(blocks.map((block) => block.zoneId));
    const sourceTasks = state.tasks
      .filter((task) => !task.trashed && !task.someday && (
        task.planDate === sourceDate || task.schedDate === sourceDate
        || (!task.planDate && !task.schedDate && task.zoneId != null && zoneIds.has(task.zoneId))
      ))
      .map((task) => captureDayPresetTask(task, `source-${task.id}`));
    setDraft({ id: undefined, name: '', blocks, tasks: sourceTasks });
    setSavedMessage(`${fmtHuman(new Date(sourceDate + 'T12:00:00'))}: завантажено ${blocks.length} зон і ${sourceTasks.length} типових задач. Назви пресет і збережи його.`);
  };

  const startNew = () => {
    setDraft(blankDraft());
    setSavedMessage('');
  };

  const startEdit = (preset: DayTemplate) => {
    setDraft({
      id: preset.id,
      name: preset.name,
      blocks: (preset.blocks || []).map((block) => ({ zoneId: block.zoneId, s: block.s, e: block.e })),
      tasks: (preset.tasks || []).map((entry) => ({ id: entry.id, task: { ...entry.task } })),
    });
    setSavedMessage(`Редагування пресету «${preset.name}». Уже створені задачі й застосовані дні збережуть свої поточні стани.`);
  };

  const addBlock = () => {
    if (!zones.length) return;
    setDraft((current) => ({ ...current, blocks: [...current.blocks, { zoneId: zones[0].id, s: '09:00', e: '10:00' }] }));
    setSavedMessage('');
  };

  const removeBlock = (index: number) => {
    setDraft((current) => ({ ...current, blocks: current.blocks.filter((_, i) => i !== index) }));
    setSavedMessage('');
  };

  const addTask = () => {
    setDraft((current) => ({
      ...current,
      tasks: [...(current.tasks || []), { id: newEntryId(), task: { title: '', type: 'simple' } }],
    }));
    setSavedMessage('');
  };

  const removeTask = (index: number) => {
    setDraft((current) => ({ ...current, tasks: (current.tasks || []).filter((_, i) => i !== index) }));
    setSavedMessage('');
  };

  const taskErrors = (draft.tasks || []).map((entry) => taskValidationError(entry, zones));
  const canSave = !!draft.name.trim()
    && draft.blocks.every((block) => zones.some((zone) => zone.id === block.zoneId) && !!normalizeDayRange(block.s, block.e))
    && taskErrors.every((error) => !error);

  const handleSave = (e: FormEvent) => {
    e.preventDefault();
    if (!canSave) return;
    saveDayTemplate({
      ...(draft.id ? { id: draft.id } : {}),
      name: draft.name.trim(),
      blocks: draft.blocks.map((block) => ({ ...block, ...normalizeDayRange(block.s, block.e)! })),
      tasks: draft.tasks || [],
    });
    setSavedMessage(`Пресет «${draft.name.trim()}» збережено. Вже застосовані дні та створені задачі залишилися без змін.`);
    setDraft(blankDraft());
  };

  const handleDelete = (id: string, presetName: string) => {
    if (!window.confirm(`Видалити пресет дня «${presetName}»? Уже застосовані дні залишаться.`)) return;
    deleteDayTemplate(id);
    if (draft.id === id) setDraft(blankDraft());
  };

  const updateType = (index: number, type: TaskType) => {
    const entry = draft.tasks?.[index];
    if (!entry) return;
    const keepZone = entry.task.zoneId;
    setPresetTask(index, {
      type,
      ...(type === 'check' ? { items: entry.task.items || [] } : { items: undefined }),
      ...(type === 'zonelinked' ? { zoneId: keepZone || zones.find((zone) => zone.id !== 0)?.id } : {}),
      ...(type === 'timewin' ? { windowStart: entry.task.windowStart || '07:00', windowEnd: entry.task.windowEnd || '09:00' } : {}),
      ...(type === 'sched' ? { schedTime: entry.task.schedTime || '09:00' } : {}),
      ...(type === 'alarm' ? { alarmTime: entry.task.alarmTime || '09:00' } : {}),
    });
  };

  return (
    <section className="tsec active day-presets" aria-labelledby="day-presets-title">
      <button type="button" className="secondary-action" onClick={() => useStore.getState().switchTab('more')}>← Ще</button>
      <h1 id="day-presets-title" className="day-presets-title"><Icon name="archive" /> Пресети дня</h1>
      <p className="day-presets-intro">
        Створи повторно використовуваний план із назвою, зонами та типовими задачами. Можна почати з реального дня або скласти пресет тут, не змінюючи календар.
        Задачі створюються як окремі разові копії для кожної дати; повторне застосування не дублює їх і не скидає виконання чи ручні правки.
      </p>

      <div className="day-presets-source">
        <label htmlFor="day-preset-source-date">Почати з дня</label>
        <div className="day-presets-source-row">
          <input
            id="day-preset-source-date"
            type="date"
            value={sourceDate}
            onChange={(e) => { setSourceDate(e.target.value as DateKey); setSavedMessage(''); }}
          />
          <button type="button" className="day-presets-control" disabled={!validDate} onClick={loadSourceDay}>Завантажити день</button>
          <button type="button" className="day-presets-control" onClick={startNew}>Новий порожній пресет</button>
        </div>
        <p className="day-presets-help">Завантаження копіює часові зони та задачі з вибраної дати у редактор. Воно не змінює сам день.</p>
      </div>

      <form className="day-presets-editor" onSubmit={handleSave}>
        <div className="day-presets-editor-head">
          <h2>{draft.id ? 'Редагування пресету' : 'Новий пресет дня'}</h2>
          {draft.id && <button type="button" className="day-presets-control" onClick={startNew}>Скасувати редагування</button>}
        </div>
        <label htmlFor="day-preset-name">Назва пресету</label>
        <input
          id="day-preset-name"
          type="text"
          value={draft.name}
          onChange={(e) => { setDraft((current) => ({ ...current, name: e.target.value })); setSavedMessage(''); }}
          placeholder="Наприклад, Робочий день"
          autoComplete="off"
          maxLength={80}
        />

        <div className="day-presets-editor-section">
          <div className="day-presets-section-head"><h3>Часові зони</h3><button type="button" className="day-presets-control" onClick={addBlock} disabled={!zones.length}>+ Додати зону</button></div>
          {draft.blocks.length ? draft.blocks.map((block, index) => (
            <div className="day-presets-block-wrap" key={`${index}-${block.zoneId}`}>
              <div className="day-presets-block-row">
                <label>
                  <span>Зона</span>
                  <select value={block.zoneId} onChange={(e) => setBlock(index, { zoneId: Number(e.target.value) })}>
                    {!zones.some((zone) => zone.id === block.zoneId) && <option value={block.zoneId}>Недоступна зона ({block.zoneId})</option>}
                    {zones.map((zone) => <option value={zone.id} key={zone.id}>{zone.nm}</option>)}
                  </select>
                </label>
                <label><span>Початок</span><input type="time" value={block.s} onChange={(e) => setBlock(index, { s: e.target.value })} /></label>
                <label><span>Кінець</span><input type="text" inputMode="numeric" maxLength={5} placeholder="HH:MM або 24:00" value={block.e} onChange={(e) => setBlock(index, { e: e.target.value })} /></label>
                <button type="button" className="day-presets-remove" aria-label={`Видалити зону ${index + 1}`} onClick={() => removeBlock(index)}>Видалити</button>
              </div>
              {blockValidationError(block, zones) && <p className="day-presets-error" role="alert">{blockValidationError(block, zones)}</p>}
            </div>
          )) : <p className="day-presets-empty">Зон поки немає. Можна зберегти пресет без зон або додати їх вручну.</p>}
        </div>

        <div className="day-presets-editor-section">
          <div className="day-presets-section-head"><h3>Типові задачі</h3><button type="button" className="day-presets-control" onClick={addTask}>+ Додати задачу</button></div>
          <p className="day-presets-help">Кожне застосування створює окрему задачу для цієї дати. Повторення та стан виконання не переносяться; тип, часове вікно й вибрана зона зберігаються.</p>
          {(draft.tasks || []).length ? draft.tasks!.map((entry, index) => {
            const type = entry.task.type as TaskType;
            return (
              <article className="day-presets-task" key={entry.id}>
                <div className="day-presets-task-head"><strong>Задача {index + 1}</strong><button type="button" className="day-presets-remove" onClick={() => removeTask(index)}>Видалити</button></div>
                <div className="day-presets-task-grid">
                  <label className="day-presets-wide"><span>Назва</span><input value={entry.task.title} maxLength={160} onChange={(e) => setPresetTask(index, { title: e.target.value })} placeholder="Що потрібно зробити?" /></label>
                  <label><span>Тип</span><select value={type} onChange={(e) => updateType(index, e.target.value as TaskType)}>{Object.entries(taskTypeCatalog).map(([key, def]) => <option value={key} key={key}>{def.label}</option>)}</select></label>
                  <label><span>Зона</span><select value={entry.task.zoneId ?? ''} onChange={(e) => setPresetTask(index, { zoneId: e.target.value ? Number(e.target.value) : null })}><option value="">Без зони</option>{zones.map((zone) => <option value={zone.id} key={zone.id}>{zone.nm}</option>)}</select></label>
                  {type === 'sched' && <label><span>Час</span><input type="time" value={entry.task.schedTime || ''} onChange={(e) => setPresetTask(index, { schedTime: e.target.value, reminderEnabled: true })} /> </label>}
                  {type === 'alarm' && <label><span>Час сигналу</span><input type="time" value={entry.task.alarmTime || ''} onChange={(e) => setPresetTask(index, { alarmTime: e.target.value })} /></label>}
                  {type === 'timewin' && <>
                    <label><span>Початок вікна</span><input type="time" value={entry.task.windowStart || '07:00'} onChange={(e) => setPresetTask(index, { windowStart: e.target.value })} /></label>
                    <label><span>Кінець вікна</span><input type="time" value={entry.task.windowEnd || '09:00'} onChange={(e) => setPresetTask(index, { windowEnd: e.target.value })} /></label>
                  </>}
                  {(type === 'counter' || type === 'negative') && <label><span>Ціль лічильника</span><input type="number" min="0" step="1" value={entry.task.counterTarget ?? (type === 'counter' ? 10 : 0)} onChange={(e) => setPresetTask(index, { counterTarget: Math.max(0, Number(e.target.value) || 0), counterEnabled: type === 'counter' })} /></label>}
                  {(type === 'counter' || type === 'negative') && <label><span>Крок</span><input type="number" min="1" step="1" value={entry.task.counterStep ?? 1} onChange={(e) => setPresetTask(index, { counterStep: Math.max(1, Number(e.target.value) || 1) })} /></label>}
                  {(type === 'counter' || type === 'negative') && <label><span>Одиниця</span><select value={entry.task.counterUnit || 'count'} onChange={(e) => setPresetTask(index, { counterUnit: e.target.value as 'count' | 'ml' | 'min' | 'kcal' })}><option value="count">Рази</option><option value="ml">мл</option><option value="min">хв</option><option value="kcal">ккал</option></select></label>}
                  {type === 'negative' && <label><span>Мінус досвіду за запис</span><input type="number" min="0" step="1" value={entry.task.negXp ?? 5} onChange={(e) => setPresetTask(index, { negXp: Math.max(0, Number(e.target.value) || 0) })} /></label>}
                  {type === 'ctx' && <label className="day-presets-wide"><span>Мітки контексту (через кому)</span><input value={(entry.task.ctxTags || []).join(',')} onChange={(e) => setPresetTask(index, { ctxTags: e.target.value.split(',') })} /></label>}
                  <label className="day-presets-wide"><span>Загальні мітки (через кому)</span><input value={(entry.task.tags || []).join(',')} onChange={(e) => setPresetTask(index, { tags: e.target.value.split(',') })} /></label>
                  {type === 'check' && <label className="day-presets-wide"><span>Кроки чекліста (по одному в рядку)</span><textarea rows={3} value={(entry.task.items || []).map((item) => item.text).join('\n')} onChange={(e) => setPresetTask(index, { items: e.target.value.split('\n').map((text) => ({ text, done: false })) })} /></label>}
                  <label className="day-presets-wide"><span>Примітка</span><textarea rows={2} value={entry.task.note || ''} onChange={(e) => setPresetTask(index, { note: e.target.value })} /></label>
                </div>
                {taskErrors[index] && <p className="day-presets-error" role="alert">{taskErrors[index]}</p>}
              </article>
            );
          }) : <p className="day-presets-empty">Типових задач поки немає. Додай їх, якщо вони часто входять до цього плану.</p>}
        </div>

        <div className="day-presets-save-row">
          <button type="submit" disabled={!canSave}><Icon name="archive" /> {draft.id ? 'Зберегти зміни' : 'Зберегти пресет'}</button>
        </div>
        {!draft.name.trim() && <p className="day-presets-help">Вкажи назву пресету.</p>}
        <p className="day-presets-status" aria-live="polite">{savedMessage}</p>
      </form>

      <div className="day-presets-list">
        <h2>Збережені пресети</h2>
        {dayTemplates.length ? dayTemplates.map((preset) => (
          <article className="day-presets-item" key={preset.id}>
            <div className="day-presets-item-main">
              <h3>{preset.name}</h3>
              <p>{(preset.blocks || []).length} зон · {(preset.tasks || []).length} типових задач</p>
              <ul>
                {(preset.blocks || []).map((block, index) => {
                  const zone = zones.find((item) => item.id === block.zoneId);
                  return <li key={`${block.zoneId}-${block.s}-${block.e}-${index}`}>{zone?.nm || 'Невідома зона'} · {block.s}–{block.e}</li>;
                })}
                {(preset.tasks || []).map((entry) => <li key={entry.id}>Задача: {entry.task.title} · {taskTypeCatalog[entry.task.type].label}</li>)}
              </ul>
            </div>
            <div className="day-presets-item-actions">
              <button type="button" className="day-presets-edit" onClick={() => startEdit(preset)}>Редагувати</button>
              <button type="button" className="day-presets-delete" aria-label={`Видалити пресет «${preset.name}»`} onClick={() => handleDelete(preset.id, preset.name)}>Видалити</button>
            </div>
          </article>
        )) : <p className="day-presets-empty">Збережених пресетів поки немає.</p>}
      </div>

      <div className="day-presets-week">
        <WeekConstructor mode="manage" />
      </div>
    </section>
  );
}
