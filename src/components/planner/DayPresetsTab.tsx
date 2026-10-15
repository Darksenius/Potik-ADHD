import { useEffect, useState } from 'react';
import { useStore } from '../../state/store';
import type { DateKey } from '../../types';
import { fmtDate, fmtHuman, isDateKey } from '../../utils/date';
import Icon from '../common/Icon';
import WeekConstructor from './WeekConstructor';
import './day-presets.css';

export default function DayPresetsTab() {
  const planSelectedDay = useStore((s) => s.planSelectedDay);
  const dayBlocks = useStore((s) => s.dayBlocks);
  const zones = useStore((s) => s.zones);
  const dayTemplates = useStore((s) => s.dayTemplates);
  const saveDayTemplate = useStore((s) => s.saveDayTemplate);
  const deleteDayTemplate = useStore((s) => s.deleteDayTemplate);

  const [sourceDate, setSourceDate] = useState<DateKey>(() => planSelectedDay || fmtDate(new Date()));
  const [name, setName] = useState('');
  const [savedMessage, setSavedMessage] = useState('');
  const validDate = isDateKey(sourceDate);
  const blocks = validDate ? dayBlocks(sourceDate) : [];
  const canSave = validDate && !!name.trim() && blocks.length > 0;

  useEffect(() => {
    if (planSelectedDay) setSourceDate(planSelectedDay);
  }, [planSelectedDay]);

  const handleSave = () => {
    if (!canSave) return;
    saveDayTemplate(sourceDate, name.trim());
    setSavedMessage(`Пресет «${name.trim()}» збережено.`);
    setName('');
  };

  const handleDelete = (id: string, presetName: string) => {
    if (!window.confirm(`Видалити пресет дня «${presetName}»?`)) return;
    deleteDayTemplate(id);
  };

  return (
    <section className="tsec active day-presets" aria-labelledby="day-presets-title">
      <button type="button" className="secondary-action" onClick={() => useStore.getState().switchTab('more')}>← Ще</button>
      <h1 id="day-presets-title" className="day-presets-title"><Icon name="archive" /> Пресети дня</h1>
      <p className="day-presets-intro">
        Спочатку налаштуй часові зони потрібного дня в Плані, а тут обери цю дату як джерело.
        Пресет зберігає часові зони; задачі, прив&apos;язані до зон, залишаються звичайними задачами й не копіюються.
      </p>

      <div className="day-presets-source">
        <label htmlFor="day-preset-source-date">Дата-джерело</label>
        <input
          id="day-preset-source-date"
          type="date"
          required
          value={sourceDate}
          onChange={(e) => { setSourceDate(e.target.value as DateKey); setSavedMessage(''); }}
        />
      </div>

      <div className="day-presets-preview" aria-live="polite">
        <h2>{validDate ? 'Зони на ' + fmtHuman(new Date(sourceDate + 'T12:00:00')) : 'Вибери дату'}</h2>
        {blocks.length ? (
          <ul>
            {blocks.map((block, index) => {
              const zone = zones.find((item) => item.id === block.zoneId);
              return (
                <li key={`${block.zoneId}-${block.s}-${block.e}-${index}`} style={{ borderLeftColor: zone?.color || '#888' }}>
                  <span>{zone?.nm || 'Невідома зона'}</span>
                  <time>{block.s}–{block.e}</time>
                </li>
              );
            })}
          </ul>
        ) : (
          <p className="day-presets-empty">На цю дату зони не налаштовані. Вибери іншу дату або налаштуй зони в Плані.</p>
        )}
      </div>

      <form className="day-presets-save" onSubmit={(e) => { e.preventDefault(); handleSave(); }}>
        <label htmlFor="day-preset-name">Назва пресету</label>
        <div className="day-presets-save-row">
          <input
            id="day-preset-name"
            type="text"
            value={name}
            onChange={(e) => { setName(e.target.value); setSavedMessage(''); }}
            placeholder="Наприклад, Робочий день"
            autoComplete="off"
          />
          <button type="submit" disabled={!canSave}><Icon name="archive" /> Зберегти пресет</button>
        </div>
        {!blocks.length && <p className="day-presets-help">Щоб зберегти пресет, спершу додай хоча б одну часову зону для цієї дати в Плані.</p>}
        <p className="day-presets-status" aria-live="polite">{savedMessage}</p>
      </form>

      <div className="day-presets-list">
        <h2>Збережені пресети</h2>
        {dayTemplates.length ? dayTemplates.map((preset) => (
          <article className="day-presets-item" key={preset.id}>
            <div className="day-presets-item-main">
              <h3>{preset.name}</h3>
              <ul>
                {(preset.blocks || []).map((block, index) => {
                  const zone = zones.find((item) => item.id === block.zoneId);
                  return <li key={`${block.zoneId}-${block.s}-${block.e}-${index}`}>{zone?.nm || 'Невідома зона'} · {block.s}–{block.e}</li>;
                })}
              </ul>
            </div>
            <button type="button" className="day-presets-delete" aria-label={`Видалити пресет «${preset.name}»`} onClick={() => handleDelete(preset.id, preset.name)}>
              Видалити
            </button>
          </article>
        )) : <p className="day-presets-empty">Збережених пресетів поки немає.</p>}
      </div>

      <div className="day-presets-week">
        <WeekConstructor mode="manage" />
      </div>
    </section>
  );
}
