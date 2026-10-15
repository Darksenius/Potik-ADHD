import { useState } from 'react';
import Modal from '../common/Modal';
import { useStore } from '../../state/store';
import type { RecurItem } from '../../types';

export default function RoutineEditor({ negative, onClose }: { negative: boolean; onClose: () => void }) {
  const [name, setName] = useState('');
  const [unit, setUnit] = useState<RecurItem['unit']>('count');
  const [color, setColor] = useState(negative ? '#e24b4a' : '#7ed321');
  const [penalty, setPenalty] = useState(5);
  return <Modal title={negative ? 'Шкідлива звичка' : 'Корисна звичка'} onClose={onClose}>
    <form onSubmit={e => {
      e.preventDefault();
      if (!name.trim()) return;
      useStore.getState().addRecur(name, unit, color, negative, penalty);
      onClose();
    }}>
      <label className="ef">Назва звички<input className="ei" required value={name} onChange={e => setName(e.target.value)} /></label>
      <label className="ef">Одиниця виміру<select className="ei" value={unit} onChange={e => setUnit(e.target.value as RecurItem['unit'])}>
        <option value="count">Рази</option><option value="check">Виконано / ні</option>
        <option value="ml">Мілілітри</option><option value="min">Хвилини</option><option value="kcal">Калорії</option>
      </select></label>
      <label className="ef">Колір звички<input className="ei" type="color" value={color} onChange={e => setColor(e.target.value)} /></label>
      {negative && <label className="ef">Штраф досвіду<input className="ei" type="number" min={1} required value={penalty} onChange={e => setPenalty(Number(e.target.value))} /></label>}
      <button className="ab" type="submit">Зберегти звичку</button>
    </form>
  </Modal>;
}
