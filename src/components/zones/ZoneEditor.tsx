import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import type { Zone } from '../../types';
import { useStore } from '../../state/store';
import { normalizeDayRange } from '../../utils/date';
import './zone-ui.css';

const PRIORITY_LEVELS = [1, 2, 3, 4, 5] as const;
const priorityDescription: Record<number, string> = {
  1: 'Найнижчий',
  2: 'Низький',
  3: 'Середній',
  4: 'Високий',
  5: 'Найвищий',
};

export default function ZoneEditor({ zone, onClose }: { zone: Zone; onClose: () => void }) {
  const [name, setName] = useState(zone.nm);
  const [color, setColor] = useState(zone.color);
  const [description, setDescription] = useState(zone.desc || '');
  const [priority, setPriority] = useState(Math.min(5, Math.max(1, Math.round(Number(zone.prio) || 1))));
  const [slots, setSlots] = useState(zone.slots.map(s => ({ ...s })));
  const [error, setError] = useState('');

  const panelRef = useRef<HTMLFormElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const onCloseRef = useRef(onClose);
  useEffect(() => { onCloseRef.current = onClose; }, [onClose]);

  useEffect(() => {
    const previousOverflow = document.body.style.overflow;
    const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    document.body.style.overflow = 'hidden';
    closeButtonRef.current?.focus();

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        onCloseRef.current();
        return;
      }
      if (event.key !== 'Tab' || !panelRef.current) return;
      const focusable = Array.from(panelRef.current.querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [href], [tabindex]:not([tabindex="-1"])'
      )).filter(element => element.getClientRects().length > 0);
      if (!focusable.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener('keydown', handleKeyDown);
      previouslyFocused?.focus();
    };
  }, []);

  const save = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!name.trim()) return;
    if (slots.some(slot => !normalizeDayRange(slot.s, slot.e))) {
      setError('Кінець має бути після початку. Для переходу через опівніч додай два проміжки: до 00:00 та від 00:00.');
      return;
    }
    useStore.getState().updateZone(zone.id, {
      nm: name.trim(), color, desc: description.trim(),
      prio: Math.min(5, Math.max(1, priority)), slots,
    });
    onClose();
  };

  return (
    <div className="zone-editor-backdrop" onClick={event => { if (event.target === event.currentTarget) onClose(); }}>
      <form ref={panelRef} className="zone-editor-shell" role="dialog" aria-modal="true" aria-labelledby="zone-editor-title" onSubmit={save}>
        <header className="zone-editor-header">
          <div>
            <p className="zone-editor-kicker">НАЛАШТУВАННЯ ЗОНИ</p>
            <h2 id="zone-editor-title">Редагування зони</h2>
            <p className="zone-editor-subtitle">Назва, колір, пріоритет і час дії</p>
          </div>
          <button ref={closeButtonRef} type="button" className="zone-editor-close" aria-label="Закрити редагування зони" onClick={onClose}>✕</button>
        </header>

        <div className="zone-editor-content">
          <section className="zone-editor-section" aria-labelledby="zone-editor-details-title">
            <div className="zone-editor-section-heading">
              <span className="zone-editor-step">01</span>
              <div>
                <h3 id="zone-editor-details-title">Основне</h3>
                <p>Як зона називається і виглядає</p>
              </div>
            </div>
            <label className="zone-editor-field">
              <span>Назва зони</span>
              <input className="ei" required value={name} onChange={event => setName(event.target.value)} />
            </label>
            <label className="zone-editor-field">
              <span>Опис</span>
              <input className="ei" value={description} onChange={event => setDescription(event.target.value)} placeholder="Коротко про цю зону" />
            </label>
            <label className="zone-editor-field zone-editor-color-field">
              <span>Колір зони</span>
              <input type="color" value={color} aria-label="Колір зони" onChange={event => setColor(event.target.value)} />
              <code>{color.toUpperCase()}</code>
            </label>

            <fieldset className="zone-priority-fieldset">
              <legend>Пріоритет <span>· 5 — найвищий</span></legend>
              <div className="zone-priority-options">
                {PRIORITY_LEVELS.map(level => (
                  <label className={'zone-priority-option' + (priority === level ? ' selected' : '')} key={level}>
                    <input type="radio" name="zone-priority" value={level} checked={priority === level} onChange={() => setPriority(level)} />
                    <span className="zone-priority-number">{level}</span>
                    <span className="zone-priority-description">{priorityDescription[level]}</span>
                  </label>
                ))}
              </div>
            </fieldset>
          </section>

          <section className="zone-editor-section zone-slots-section" aria-labelledby="zone-editor-slots-title">
            <div className="zone-editor-section-heading">
              <span className="zone-editor-step">02</span>
              <div>
                <h3 id="zone-editor-slots-title">Часові проміжки</h3>
                <p>Зона може діяти в кілька проміжків протягом дня</p>
              </div>
            </div>
            <div className="zone-slot-list">
              {slots.length ? slots.map((slot, index) => (
                <div className="zone-slot-card" key={index}>
                  <div className="zone-slot-card-heading">
                    <span>Проміжок {String(index + 1).padStart(2, '0')}</span>
                    <button type="button" className="zone-slot-remove" aria-label={'Видалити проміжок ' + (index + 1)} onClick={() => setSlots(current => current.filter((_, i) => i !== index))}>Видалити</button>
                  </div>
                  <div className="zone-slot-times">
                    <label>
                      <span>Початок</span>
                      <input type="time" required value={slot.s} onChange={event => setSlots(current => current.map((item, i) => i === index ? { ...item, s: event.target.value } : item))} />
                    </label>
                    <span className="zone-slot-dash">→</span>
                    <label>
                      <span>Кінець</span>
                      <input type="time" required value={slot.e === '24:00' ? '00:00' : slot.e} onChange={event => setSlots(current => current.map((item, i) => i === index ? { ...item, e: event.target.value === '00:00' ? '24:00' : event.target.value } : item))} />
                    </label>
                  </div>
                </div>
              )) : (
                <div className="zone-slot-empty">Проміжків поки немає. Додай час або збережи зону без щоденного розкладу.</div>
              )}
            </div>
            <button type="button" className="zone-slot-add" onClick={() => setSlots(current => [...current, { s: '09:00', e: '10:00' }])}>
              <span>＋</span> Додати часовий проміжок
            </button>
            {error && <p className="zone-editor-error" role="alert">{error}</p>}
          </section>
        </div>

        <footer className="zone-editor-footer">
          <span className="zone-editor-hint">Зміни збережуться для цієї зони в усіх планах.</span>
          <div>
            <button type="button" className="zone-editor-cancel" onClick={onClose}>Скасувати</button>
            <button type="submit" className="zone-editor-save">Зберегти зміни</button>
          </div>
        </footer>
      </form>
    </div>
  );
}
