import { useState } from 'react';
import type { Zone } from '../../types';
import { useStore } from '../../state/store';
import Modal from '../common/Modal';

export default function ZoneEditor({ zone, onClose }: { zone: Zone; onClose: () => void }) {
  const [name, setName] = useState(zone.nm);
  const [color, setColor] = useState(zone.color);
  const [description, setDescription] = useState(zone.desc || '');
  const [priority, setPriority] = useState(zone.prio || 1);
  const [slots, setSlots] = useState(zone.slots.map(s => ({ ...s })));
  return <Modal title="Редагування зони" onClose={onClose}>
    <form onSubmit={e => {
      e.preventDefault();
      if (!name.trim()) return;
      useStore.getState().updateZone(zone.id, { nm: name.trim(), color, desc: description.trim(), prio: priority, slots });
      onClose();
    }}>
      <label className="ef">Назва зони<input className="ei" required value={name} onChange={e => setName(e.target.value)} /></label>
      <label className="ef">Колір<input className="ei" type="color" value={color} onChange={e => setColor(e.target.value)} /></label>
      <label className="ef">Опис<input className="ei" value={description} onChange={e => setDescription(e.target.value)} /></label>
      <label className="ef">Пріоритет<select className="ei" value={priority} onChange={e => setPriority(Number(e.target.value))}>
        <option value={1}>Фон</option><option value={2}>Нарада</option><option value={3}>Критичне</option>
      </select></label>
      {slots.map((slot, i) => <div className="slot-row" key={i}>
        <input aria-label={'Початок ' + (i + 1)} type="time" required value={slot.s} onChange={e => setSlots(ss => ss.map((s, j) => j === i ? { ...s, s: e.target.value } : s))} />
        <span>—</span>
        <input aria-label={'Кінець ' + (i + 1)} type="time" required value={slot.e === '24:00' ? '00:00' : slot.e} onChange={e => setSlots(ss => ss.map((s, j) => j === i ? { ...s, e: e.target.value === '00:00' ? '24:00' : e.target.value } : s))} />
        <button type="button" aria-label={'Видалити проміжок ' + (i + 1)} onClick={() => setSlots(ss => ss.filter((_, j) => j !== i))}>✕</button>
      </div>)}
      <button type="button" className="ab" onClick={() => setSlots(ss => [...ss, { s: '09:00', e: '10:00' }])}>+ Часовий проміжок</button>
      <button className="ab" type="submit">Зберегти зону</button>
    </form>
  </Modal>;
}
