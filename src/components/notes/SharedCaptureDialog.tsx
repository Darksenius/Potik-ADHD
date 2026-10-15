import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { useStore } from '../../state/store';
import { flushAutosave } from '../../services/autosave';
import { getSaveErrorMessage } from '../../services/persistence';
import { getSaveStatus, subscribeSaveStatus } from '../../services/saveStatus';
import '../../styles/shared-capture.css';

/** Incoming shares are already durable notes before this optional choice appears. */
export default function SharedCaptureDialog() {
  const ids = useStore(s => s.sharedNoteIds);
  const notes = useStore(s => s.qnotes);
  const folders = useStore(s => s.folders);
  const editorTaskId = useStore(s => s.editorTaskId);
  const note = notes.find(n => n.id === ids[0]);
  const [error, setError] = useState('');
  const [text, setText] = useState('');
  const status = useSyncExternalStore(subscribeSaveStatus, getSaveStatus, getSaveStatus);
  const panel = useRef<HTMLDivElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (ids.length && !note) useStore.getState().dismissSharedNote(ids[0]);
  }, [ids, note]);
  useEffect(() => {
    if (!note || editorTaskId !== null) return;
    setError('');
    setText(note.txt);
    const focused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeButton.current?.focus();
    const keydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); close(); }
      if (event.key !== 'Tab' || !panel.current) return;
      const controls = Array.from(panel.current.querySelectorAll<HTMLElement>('button:not([disabled]), textarea, select'));
      const first = controls[0], last = controls[controls.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    };
    window.addEventListener('keydown', keydown);
    return () => { window.removeEventListener('keydown', keydown); focused?.focus(); };
  }, [note?.id, editorTaskId]);

  function close() {
    if (!note) return;
    if (!flushAutosave()) { setError(getSaveErrorMessage()); return; }
    useStore.getState().dismissSharedNote(note.id);
  }
  function makeTask() {
    if (!note || !text.trim()) return;
    if (!flushAutosave()) { setError(getSaveErrorMessage()); return; }
    const s = useStore.getState();
    const before = { tasks: s.tasks, nid: s.nid, qnotes: s.qnotes };
    const [title, ...details] = text.trim().split('\n');
    const id = s.saveTask(null, { title: title.trim(), type: 'simple', note: details.join('\n'), folderId: note.folder || null });
    s.deleteQuickNote(note.id);
    if (!flushAutosave()) { const message = getSaveErrorMessage(); useStore.setState(before); setError(message); return; }
    s.dismissSharedNote(note.id);
    s.requestEditTask(id);
    s.showToast('Задачу збережено. Можна уточнити час і зону.');
  }
  if (!note || editorTaskId !== null) return null;
  return <div className="shared-capture-backdrop">
    <div className="shared-capture-dialog" ref={panel} role="dialog" aria-modal="true" aria-labelledby="shared-capture-title">
      <header><h2 id="shared-capture-title">Зберегти в Потік</h2><button ref={closeButton} type="button" aria-label="Закрити отриманий запис" onClick={close}>✕</button></header>
      <p>Текст уже записано як нотатку. Можеш уточнити його або зробити задачею.</p>
      <label htmlFor="shared-capture-text">Текст<textarea id="shared-capture-text" rows={6} value={text} onChange={e => { setText(e.target.value); if (e.target.value.trim()) useStore.getState().updateQuickNote(note.id, e.target.value, note.folder); setError(''); }} /></label>
      {!text.trim() && <p>Порожнє поле не замінить отриманий запис. Попередній текст лишається у Нотатках.</p>}
      <label htmlFor="shared-capture-folder">Папка<select id="shared-capture-folder" value={note.folder} onChange={e => useStore.getState().updateQuickNote(note.id, text.trim() ? text : note.txt, e.target.value)}>
        {!folders.some(f => f.id === note.folder) && <option value={note.folder}>Без папки</option>}{folders.map(f => <option key={f.id} value={f.id}>{f.nm}</option>)}
      </select></label>
      <p role="status">{status.phase === 'pending' ? 'Зберігаю…' : status.phase === 'error' ? 'Запис ще не збережено' : 'Нотатку збережено'}</p>
      {(error || status.phase === 'error') && <p role="alert">{error || status.message}</p>}
      <footer><button type="button" onClick={close}>Залишити нотаткою</button><button type="button" disabled={!text.trim()} onClick={makeTask}>Зробити задачею</button></footer>
    </div>
  </div>;
}
