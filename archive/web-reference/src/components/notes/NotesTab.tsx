import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { useStore } from '../../state/store';
import { flushAutosave } from '../../services/autosave';
import { getSaveStatus, subscribeSaveStatus } from '../../services/saveStatus';
import { getSaveErrorMessage } from '../../services/persistence';
import Icon from '../common/Icon';
import ClipboardFallback from '../common/ClipboardFallback';
import '../../styles/notes-focus-parity.css';
import '../../styles/notes-workspace.css';

export default function NotesTab() {
  const notepad = useStore(s => s.notepad);
  const draftFolder = useStore(s => s.notepadFolder);
  const setNotepad = useStore(s => s.setNotepad);
  const setNotepadFolder = useStore(s => s.setNotepadFolder);
  const saveNotepadAsNote = useStore(s => s.saveNotepadAsNote);
  const folders = useStore(s => s.folders);
  const qnotes = useStore(s => s.qnotes);
  const noteFolder = useStore(s => s.noteFolder);
  const setNoteFolder = useStore(s => s.setNoteFolder);
  const updateQuickNote = useStore(s => s.updateQuickNote);
  const deleteQuickNote = useStore(s => s.deleteQuickNote);
  const saveStatus = useSyncExternalStore(subscribeSaveStatus, getSaveStatus, getSaveStatus);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editText, setEditText] = useState('');
  const [editFolder, setEditFolder] = useState('');
  const [actionError, setActionError] = useState('');
  const [copyFeedback, setCopyFeedback] = useState<{ id: number; message: string } | null>(null);
  const [clipboardFallbackText, setClipboardFallbackText] = useState<string | null>(null);
  const draftRef = useRef<HTMLTextAreaElement>(null);
  const editButtons = useRef<Record<number, HTMLButtonElement | null>>({});
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (copyTimer.current) clearTimeout(copyTimer.current); }, []);

  const destination = folders.some(f => f.id === draftFolder) ? draftFolder : folders[0]?.id || '';
  const visibleNotes = noteFolder === 'all' ? qnotes : qnotes.filter(n => n.folder === noteFolder);
  const words = notepad.trim() ? notepad.trim().split(/\s+/).length : 0;
  const statusText = saveStatus.phase === 'pending' ? 'Зберігаю…' : saveStatus.phase === 'error'
    ? 'Не збережено' : notepad ? 'Чернетку збережено' : 'Автозбереження увімкнено';
  const folderNameFor = (id: string) => folders.find(folder => folder.id === id)?.nm || 'Без папки';

  function fileDraft() {
    if (!notepad.trim()) return;
    if (!flushAutosave()) { setActionError(getSaveErrorMessage()); return; }
    const state = useStore.getState();
    const before = { qnotes: state.qnotes, qnid: state.qnid, notepad: state.notepad, notepadFolder: state.notepadFolder };
    const note = saveNotepadAsNote(destination);
    if (!note) return;
    if (!flushAutosave()) {
      const error = getSaveErrorMessage();
      useStore.setState(before);
      setActionError('Не вдалося додати до списку. Текст повернуто в поле. ' + error);
      return;
    }
    const latest = useStore.getState();
    latest.award(1);
    if (note.folder === 'impulse') latest.scheduleNoteReview(note.id);
    if (noteFolder !== 'all') setNoteFolder(note.folder);
    setActionError('');
    latest.showToast('Нотатку додано до списку');
    draftRef.current?.focus();
  }

  function makeTask() {
    const text = notepad.trim();
    if (!text || !flushAutosave()) { if (text) setActionError(getSaveErrorMessage()); return; }
    const body = text.startsWith('...') ? text.slice(3).trim() : text;
    if (!body) { useStore.getState().requestNewTaskEditor(); return; }
    const lines = body.split('\n');
    const state = useStore.getState();
    const before = { tasks: state.tasks, nid: state.nid, notepad: state.notepad };
    const id = state.saveTask(null, { title: lines[0].trim(), type: 'simple', note: lines.slice(1).join('\n') });
    setNotepad('');
    if (!flushAutosave()) {
      const error = getSaveErrorMessage();
      useStore.setState(before);
      setActionError(error);
      return;
    }
    state.award(10);
    state.requestEditTask(id);
    state.showToast('Задачу створено. Решта тексту — у її нотатці.');
  }

  async function copyNote(id: number, text: string) {
    try {
      if (!navigator.clipboard) throw new Error('Clipboard unavailable');
      await navigator.clipboard.writeText(text);
      setCopyFeedback({ id, message: 'Скопійовано' });
    } catch {
      setCopyFeedback({ id, message: 'Відкрито для копіювання' });
      setClipboardFallbackText(text);
    }
    if (copyTimer.current) clearTimeout(copyTimer.current);
    copyTimer.current = setTimeout(() => setCopyFeedback(null), 1600);
  }

  function closeEditor() {
    const id = editingId;
    setEditingId(null); setEditText(''); setEditFolder('');
    requestAnimationFrame(() => (id === null ? draftRef.current : editButtons.current[id] || draftRef.current)?.focus());
  }

  function saveEdit() {
    if (editingId === null || !editText.trim()) return;
    const before = useStore.getState().qnotes;
    updateQuickNote(editingId, editText, editFolder);
    if (!flushAutosave()) {
      const error = getSaveErrorMessage();
      useStore.setState({ qnotes: before }); setActionError(error); return;
    }
    if (noteFolder !== 'all' && editFolder !== noteFolder) setNoteFolder('all');
    setActionError(''); closeEditor();
  }

  function retrySave() {
    if (editingId !== null) { saveEdit(); return; }
    if (flushAutosave()) setActionError('');
  }

  return <section id="notes-sec" className="tsec active notes-workspace">
    <header className="notes-page-heading"><h2>Нотатки</h2></header>
    {editingId === null && <section className="note-composer" aria-labelledby="note-composer-title">
      <div className="note-composer-heading"><label id="note-composer-title" htmlFor="notepad">Нотатка</label>
        <span className={'note-save-status ' + saveStatus.phase} role="status" aria-live="polite"><Icon name={saveStatus.phase === 'saved' ? 'check' : 'clock'} size={14} /> {statusText}</span>
      </div>
      <textarea ref={draftRef} id="notepad" className="tnote" value={notepad} rows={4}
        onChange={e => { setNotepad(e.target.value); setActionError(''); }}
        onBlur={() => { if (getSaveStatus().phase === 'pending') flushAutosave(); }}
        placeholder="Напиши думку…" aria-describedby="note-autosave-hint"
        onKeyDown={e => { if (!e.nativeEvent.isComposing && e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); fileDraft(); } }} />
      <div className="note-composer-meta"><p id="note-autosave-hint">Текст зберігається автоматично, без кнопки.</p><span>{words} слів · {notepad.length} симв</span></div>
      <label className="note-destination" htmlFor="note-draft-folder"><span>Папка для нотатки</span><select id="note-draft-folder" className="ei" aria-label="Папка нотатки" value={destination} onChange={e => setNotepadFolder(e.target.value)}>
        {!folders.length && <option value="">Без папки</option>}{folders.map(folder => <option value={folder.id} key={folder.id}>{folder.nm}</option>)}
      </select></label>
      <div className="note-composer-actions"><button className="note-file-button" type="button" disabled={!notepad.trim()} onClick={fileDraft}><Icon name="plus" /> Додати до нотаток</button>
        <button className="note-task-button" type="button" disabled={!notepad.trim()} onClick={makeTask}><Icon name="quest" /> Зробити задачею</button>
      </div>
      <p className="note-file-hint">«Додати до нотаток» переносить текст у список нижче й відкриває чисте поле.</p>
    </section>}
    {(actionError || saveStatus.phase === 'error') && <div className="note-save-error" role="alert"><p>{actionError || saveStatus.message}</p><button type="button" onClick={retrySave}>Повторити збереження</button></div>}

    <section className="notes-collection" aria-labelledby="notes-collection-title">
      <div className="notes-collection-heading"><h3 id="notes-collection-title">Твої нотатки <span>{qnotes.length}</span></h3>
      </div>
      <div id="folder-tabs" className="notes-folder-filters" role="group" aria-label="Фільтр нотаток">
        <button type="button" className={'ftab' + (noteFolder === 'all' ? ' act' : '')} aria-pressed={noteFolder === 'all'} onClick={() => setNoteFolder('all')}>Усі</button>
        {folders.map(folder => <button type="button" className={'ftab' + (noteFolder === folder.id ? ' act' : '')} aria-pressed={noteFolder === folder.id} key={folder.id} onClick={() => setNoteFolder(folder.id)}>{folder.ico} {folder.nm} <span>{qnotes.filter(note => note.folder === folder.id).length}</span></button>)}
      </div>
      <div id="qn-list">{!visibleNotes.length ? <p className="notes-empty">{noteFolder === 'all' ? 'Тут з’являться додані нотатки. Поточний текст уже зберігається у полі вище.' : 'У цій папці ще немає нотаток.'}</p>
        : visibleNotes.map(note => <article className="qn-row note-card" key={note.id}>
          {editingId === note.id ? <form aria-label="Редагувати нотатку" className="note-edit-form" onSubmit={e => { e.preventDefault(); saveEdit(); }} onKeyDown={e => {
            if (e.nativeEvent.isComposing) return;
            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); saveEdit(); }
            if (e.key === 'Escape') { e.preventDefault(); closeEditor(); }
          }}>
            <label className="el" htmlFor="quick-note-text">Текст нотатки</label><textarea id="quick-note-text" className="tnote" rows={6} autoFocus required value={editText} onChange={e => setEditText(e.target.value)} />
            <label className="el" htmlFor="quick-note-folder">Папка</label><select id="quick-note-folder" className="ei" value={editFolder} onChange={e => setEditFolder(e.target.value)}>
              {!folders.some(folder => folder.id === editFolder) && <option value={editFolder}>Без папки</option>}{folders.map(folder => <option value={folder.id} key={folder.id}>{folder.nm}</option>)}
            </select>
            <p className="note-file-hint">Enter — новий рядок. Ctrl/⌘ + Enter — зберегти зміни. Escape — скасувати.</p>
            <div className="note-edit-actions"><button className="note-file-button" type="submit" disabled={!editText.trim()}>Зберегти нотатку</button><button className="note-task-button" type="button" onClick={closeEditor}>Скасувати</button></div>
          </form> : <>
            <div className="note-card-meta"><span><Icon name="folder" size={14} /> {folderNameFor(note.folder)}</span><time>{note.date ? new Date(note.date + 'T12:00:00').toLocaleDateString('uk', { day: 'numeric', month: 'short' }) + ' · ' : ''}{note.time}</time></div>
            <div className="qn-txt">{note.txt}</div>
            <div className="note-card-actions"><span className="notes-copy-feedback" role="status">{copyFeedback?.id === note.id ? copyFeedback.message : ''}</span>
              <button type="button" onClick={() => void copyNote(note.id, note.txt)} aria-label="Копіювати нотатку" title="Копіювати нотатку">⎘</button>
              <button ref={button => { editButtons.current[note.id] = button; }} type="button" aria-label="Редагувати нотатку" title="Редагувати нотатку" onClick={() => { setEditingId(note.id); setEditText(note.txt); setEditFolder(note.folder); setActionError(''); }}><Icon name="edit" /></button>
              <button type="button" aria-label="Видалити нотатку" title="Видалити нотатку" onClick={() => { if (window.confirm('Видалити цю нотатку?')) deleteQuickNote(note.id); }}><Icon name="trash" /></button>
            </div>
          </>}
        </article>)}
      </div>
    </section>
    {clipboardFallbackText && <ClipboardFallback text={clipboardFallbackText} onClose={() => setClipboardFallbackText(null)} />}
  </section>;
}
