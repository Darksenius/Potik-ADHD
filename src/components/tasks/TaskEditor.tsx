import { useState } from 'react';
import { useStore } from '../../state/store';
import type { Task, TaskType } from '../../types';
import { WD } from '../../utils/date';

const TYPE_OPTIONS: { value: TaskType; label: string }[] = [
  { value: 'simple', label: '☑ Проста' },
  { value: 'check', label: '≡ Чекліст' },
  { value: 'counter', label: '+N Лічильник' },
  { value: 'note', label: '✎ З нотаткою' },
  { value: 'alarm', label: '⏰ Будильник' },
  { value: 'sched', label: '📅 Запланована' },
  { value: 'timewin', label: '⏱ Вікно часу' },
  { value: 'pomodoro', label: '⏱ Pomodoro' },
  { value: 'habit', label: '◉ Звичка' },
  { value: 'kid', label: '★ Дитяча' },
  { value: 'ctx', label: '◎ Контекст' },
  { value: 'negative', label: '⚠ Шкідлива' },
  { value: 'zonelinked', label: '🔗 До зони' },
];

export default function TaskEditor() {
  const editorTaskId = useStore((s) => s.editorTaskId);
  const tasks = useStore((s) => s.tasks);
  const zones = useStore((s) => s.zones);
  const folders = useStore((s) => s.folders);
  const saveTask = useStore((s) => s.saveTask);
  const snoozeTask = useStore((s) => s.snoozeTask);
  const addFolder = useStore((s) => s.addFolder);
  const closeTaskEditor = useStore((s) => s.closeTaskEditor);

  const isNew = editorTaskId === 'new';
  const existing = !isNew && editorTaskId != null ? tasks.find((t) => t.id === editorTaskId) : null;

  // editRepDays/editId — рядки 1953-1955: локальний стан форми, не глобальний стор.
  const [title, setTitle] = useState(existing?.title || '');
  const [type, setType] = useState<TaskType>(existing?.type || 'simple');
  const [zoneId, setZoneId] = useState<string>(existing?.zoneId ? String(existing.zoneId) : '');
  const [folderId, setFolderId] = useState<string>(existing?.folderId || '');
  const [repeat, setRepeat] = useState<Task['repeat']>(existing?.repeat || 'none');
  const [repeatDays, setRepeatDays] = useState<boolean[]>(
    existing?.repeatDays && existing.repeatDays.length === 7 ? existing.repeatDays : [false, false, false, false, false, false, false]
  );
  const [repeatInterval, setRepeatInterval] = useState(existing?.repeatInterval || 30);
  const [repeatUnit, setRepeatUnit] = useState(existing?.repeatUnit || 'min');
  const [tagsText, setTagsText] = useState((existing?.tags || []).join(', '));
  const [planDate, setPlanDate] = useState(existing?.planDate || '');
  const [alarmTime, setAlarmTime] = useState(existing?.alarmTime || '');
  const [schedDate, setSchedDate] = useState(existing?.schedDate || '');
  const [schedTime, setSchedTime] = useState(existing?.schedTime || '');
  const [winStart, setWinStart] = useState(existing?.windowStart || '07:00');
  const [winEnd, setWinEnd] = useState(existing?.windowEnd || '09:00');
  const [note, setNote] = useState(existing?.note || '');
  const [cntTgt, setCntTgt] = useState(existing?.counterTarget || 10);
  const [negXp, setNegXp] = useState(existing?.negXp || 5);

  if (editorTaskId == null) return null;
  if (!isNew && !existing) return null;

  const toggleDay = (i: number) => setRepeatDays((d) => d.map((v, idx) => (idx === i ? !v : v)));

  const handleSave = () => {
    if (!title.trim()) return;
    saveTask(isNew ? null : existing!.id, {
      title,
      type,
      zoneId: zoneId ? Number(zoneId) : null,
      folderId: folderId || null,
      repeat,
      repeatDays,
      repeatInterval,
      repeatUnit,
      tags: tagsText.split(',').map((s) => s.trim()).filter(Boolean),
      planDate: planDate || undefined,
      alarmTime,
      schedDate,
      schedTime,
      windowStart: winStart,
      windowEnd: winEnd,
      note,
      counterTarget: cntTgt,
      negXp,
    });
    closeTaskEditor();
  };

  return (
    <div id="edit-page" className="open">
      <div id="edit-header">
        <button className="edit-back" onClick={closeTaskEditor}>← Назад</button>
        <h2>{isNew ? '+ Нова задача' : title || 'Редагування'}</h2>
      </div>
      <div id="edit-body">
        <div className="ef">
          <label className="el">Назва</label>
          <input type="text" className="ei" value={title} onChange={(e) => setTitle(e.target.value)} autoFocus />
        </div>

        <div className="ef">
          <label className="el">Тип</label>
          <select className="ei" value={type} onChange={(e) => setType(e.target.value as TaskType)}>
            {TYPE_OPTIONS.map((o) => (
              <option value={o.value} key={o.value}>{o.label}</option>
            ))}
          </select>
        </div>

        {type === 'negative' && (
          <div className="ef">
            <label className="el">Штраф досвіду</label>
            <input type="number" className="ei" min={1} value={negXp} onChange={(e) => setNegXp(parseInt(e.target.value) || 5)} />
          </div>
        )}

        <div className="ef">
          <label className="el">Зона</label>
          <select className="ei" value={zoneId} onChange={(e) => setZoneId(e.target.value)}>
            <option value="">Без зони</option>
            {zones.map((z) => (
              <option value={z.id} key={z.id}>{z.nm}</option>
            ))}
          </select>
        </div>

        <div className="ef">
          <label className="el">Папка</label>
          <div style={{ display: 'flex', gap: 5, alignItems: 'center' }}>
            <select className="ei" style={{ flex: 1 }} value={folderId} onChange={(e) => setFolderId(e.target.value)}>
              <option value="">— без папки —</option>
              {folders.map((f) => (
                <option value={f.id} key={f.id}>{f.ico || '📁'} {f.nm}</option>
              ))}
            </select>
            <button
              type="button"
              onClick={() => {
                const nm = window.prompt("Назва нової папки:");
                if (!nm || !nm.trim()) return;
                const ico = window.prompt('Іконка (емодзі), необов\'язково:', '📁');
                const id = addFolder(nm.trim(), ico || '📁');
                if (id) setFolderId(id);
              }}
              style={{ background: 'var(--s3)', border: '1px solid var(--b2)', borderRadius: 7, padding: '7px 9px', color: 'var(--t2)', fontFamily: "'Syne',sans-serif", fontSize: 12, cursor: 'pointer', whiteSpace: 'nowrap' }}
              title="Створити нову папку"
            >
              + Нова
            </button>
          </div>
        </div>

        {!isNew && (
          <div className="ef">
            <label className="el">Перенести / відкласти</label>
            <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap', marginBottom: 6 }}>
              {[15, 60, 180].map((min) => (
                <button
                  key={min}
                  type="button"
                  onClick={() => {
                    snoozeTask(existing!.id, min);
                    closeTaskEditor();
                  }}
                  style={{ background: 'var(--s3)', border: '1px solid var(--b2)', borderRadius: 7, padding: '6px 10px', color: 'var(--t2)', fontFamily: "'Syne',sans-serif", fontSize: 12, cursor: 'pointer' }}
                >
                  {min < 60 ? `+${min} хв` : `+${min / 60} год`}
                </button>
              ))}
              <button
                type="button"
                onClick={() => {
                  snoozeTask(existing!.id, 'tomorrow');
                  closeTaskEditor();
                }}
                style={{ background: 'var(--s3)', border: '1px solid var(--b2)', borderRadius: 7, padding: '6px 10px', color: 'var(--t2)', fontFamily: "'Syne',sans-serif", fontSize: 12, cursor: 'pointer' }}
              >
                Завтра
              </button>
            </div>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
              <span style={{ fontSize: 11, color: 'var(--t3)' }}>На день:</span>
              <input type="date" className="ei" style={{ flex: 1 }} value={planDate} onChange={(e) => setPlanDate(e.target.value)} />
            </div>
          </div>
        )}

        <div className="ef">
          <label className="el">Повторення</label>
          <select className="ei" value={repeat} onChange={(e) => setRepeat(e.target.value as Task['repeat'])}>
            <option value="none">Без повторення</option>
            <option value="daily">Щодня</option>
            <option value="weekly">Щотижня</option>
            <option value="weekdays">Пн – Пт</option>
            <option value="weekend">Вихідні</option>
            <option value="everyzone">Кожна зона (для 🔗)</option>
            <option value="interval">Через X (хв/год/дн/тиж/міс)</option>
            <option value="custom">Свій графік</option>
          </select>
        </div>

        {repeat === 'interval' && (
          <div className="ef">
            <label className="el">Повторювати кожні</label>
            <div style={{ display: 'flex', gap: 7, alignItems: 'center' }}>
              <input
                type="number"
                className="ei"
                min={1}
                value={repeatInterval}
                onChange={(e) => setRepeatInterval(parseInt(e.target.value) || 30)}
                style={{ width: 80 }}
              />
              <select className="ei" style={{ flex: 1 }} value={repeatUnit} onChange={(e) => setRepeatUnit(e.target.value as typeof repeatUnit)}>
                <option value="min">хвилин</option>
                <option value="hour">годин</option>
                <option value="day">днів</option>
                <option value="week">тижнів</option>
                <option value="month">місяців</option>
              </select>
            </div>
          </div>
        )}

        {repeat === 'custom' && (
          <div className="ef">
            <label className="el">Дні тижня</label>
            <div className="rep-days">
              {WD.map((d, i) => (
                <div className={'rd' + (repeatDays[i] ? ' on' : '')} key={i} onClick={() => toggleDay(i)}>{d}</div>
              ))}
            </div>
          </div>
        )}

        {type === 'alarm' && (
          <div className="ef">
            <label className="el">Час будильника</label>
            <input type="time" className="ei" style={{ fontFamily: "'Space Mono',monospace" }} value={alarmTime} onChange={(e) => setAlarmTime(e.target.value)} />
          </div>
        )}

        {type === 'sched' && (
          <div className="ef">
            <label className="el">Дата і час події</label>
            <div style={{ display: 'flex', gap: 6 }}>
              <input type="date" className="ei" style={{ flex: 1 }} value={schedDate} onChange={(e) => setSchedDate(e.target.value)} />
              <input type="time" className="ei" style={{ flex: 1, fontFamily: "'Space Mono',monospace" }} value={schedTime} onChange={(e) => setSchedTime(e.target.value)} />
            </div>
          </div>
        )}

        {type === 'timewin' && (
          <div className="ef">
            <label className="el">Вікно часу (від — до)</label>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
              <input type="time" className="ei" style={{ flex: 1, fontFamily: "'Space Mono',monospace" }} value={winStart} onChange={(e) => setWinStart(e.target.value)} />
              <span style={{ color: 'var(--t3)' }}>—</span>
              <input type="time" className="ei" style={{ flex: 1, fontFamily: "'Space Mono',monospace" }} value={winEnd} onChange={(e) => setWinEnd(e.target.value)} />
            </div>
          </div>
        )}

        {type === 'note' && (
          <div className="ef">
            <label className="el">Нотатка</label>
            <textarea className="ei" style={{ minHeight: 90, resize: 'vertical', lineHeight: 1.6 }} value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
        )}

        {type === 'counter' && (
          <div className="ef">
            <label className="el">Ціль лічильника</label>
            <input type="number" className="ei" min={1} value={cntTgt} onChange={(e) => setCntTgt(parseInt(e.target.value) || 10)} />
          </div>
        )}

        <div className="ef">
          <label className="el">Теги (через кому)</label>
          <input type="text" className="ei" placeholder="робота, важливо" value={tagsText} onChange={(e) => setTagsText(e.target.value)} />
        </div>

        <button id="edit-save-fab" onClick={handleSave}>
          <span className="plus">{isNew ? '＋' : '✎'}</span> {isNew ? 'Створити задачу' : 'Зберегти зміни'}
        </button>
      </div>
    </div>
  );
}
