import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useStore } from '../../state/store';
import ExportImportSection from './ExportImportSection';
import Icon from '../common/Icon';
import ClipboardFallback from '../common/ClipboardFallback';
import '../../styles/notes-focus-parity.css';

export default function NotesTab() {
  const notepad = useStore((s) => s.notepad);
  const setNotepad = useStore((s) => s.setNotepad);
  const saveNotepadAsNote = useStore((s) => s.saveNotepadAsNote);
  const folders = useStore((s) => s.folders);
  const qnotes = useStore((s) => s.qnotes);
  const noteFolder = useStore((s) => s.noteFolder);
  const setNoteFolder = useStore((s) => s.setNoteFolder);
  const addQuickNote = useStore((s) => s.addQuickNote);
  const createTask = useStore((s) => s.createTask);
  const award = useStore((s) => s.award);
  const requestNewTaskEditor = useStore((s) => s.requestNewTaskEditor);
  const requestEditTask = useStore((s) => s.requestEditTask);
  const addFolder = useStore((s) => s.addFolder);
  const updateQuickNote = useStore((s) => s.updateQuickNote);
  const deleteQuickNote = useStore((s) => s.deleteQuickNote);
  const deleteFolder = useStore((s) => s.deleteFolder);
  const getFolderIcon = useStore((s) => s.getFolderIcon);

  const [qnText, setQnText] = useState('');
  const [qnFolder, setQnFolder] = useState('impulse');
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editText, setEditText] = useState('');
  const [editFolder, setEditFolder] = useState('');
  const quickNoteRef = useRef<HTMLInputElement>(null);
  const editButtons = useRef<Record<number, HTMLButtonElement | null>>({});
  const feedbackTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [saveFeedback, setSaveFeedback] = useState('');
  const [copyFeedback, setCopyFeedback] = useState<{ id: number; message: string } | null>(null);
  const [clipboardFallbackText, setClipboardFallbackText] = useState<string | null>(null);
  const [addingFolder, setAddingFolder] = useState(false);
  const [folderName, setFolderName] = useState('');
  const [folderIcon, setFolderIcon] = useState('📁');

  useEffect(() => () => {
    if (feedbackTimer.current) clearTimeout(feedbackTimer.current);
    if (copyTimer.current) clearTimeout(copyTimer.current);
  }, []);

  const visibleNotes = noteFolder === 'all' ? qnotes : qnotes.filter((n) => n.folder === noteFolder);

  const send = () => {
    const t = qnText.trim();
    if (!t) return;
    if (t === '...') {
      setQnText('');
      requestNewTaskEditor();
      return;
    }
    if (t.startsWith('...')) {
      const title = t.slice(3).trim();
      if (!title) return;
      setQnText('');
      const task = createTask(title, 'simple');
      award(10);
      requestEditTask(task.id);
      return;
    }
    const note = addQuickNote(t, qnFolder);
    award(1);
    if (qnFolder === 'impulse') useStore.getState().scheduleNoteReview(note.id);
    setQnText('');
  };

  const savePad = () => {
    if (!notepad.trim()) return;
    saveNotepadAsNote();
    setSaveFeedback('Додано в нотатки');
    if (feedbackTimer.current) clearTimeout(feedbackTimer.current);
    feedbackTimer.current = setTimeout(() => setSaveFeedback(''), 2000);
  };

  const saveFolder = (event: FormEvent) => {
    event.preventDefault();
    const id = addFolder(folderName, folderIcon);
    if (!id) return;
    setFolderName('');
    setFolderIcon('📁');
    setAddingFolder(false);
  };

  const copyNote = async (id: number, text: string) => {
    try {
      if (!navigator.clipboard) throw new Error('Clipboard unavailable');
      await navigator.clipboard.writeText(text);
      setCopyFeedback({ id, message: 'Скопійовано' });
    } catch {
      setCopyFeedback({ id, message: 'Не вдалося скопіювати' });
      setClipboardFallbackText(text);
    }
    if (copyTimer.current) clearTimeout(copyTimer.current);
    copyTimer.current = setTimeout(() => setCopyFeedback(null), 1600);
  };

  const closeEditor = () => {
    const id = editingId;
    setEditingId(null);
    setEditText('');
    setEditFolder('');
    requestAnimationFrame(() => {
      const target = id === null ? null : editButtons.current[id];
      (target || quickNoteRef.current)?.focus();
    });
  };

  const saveEdit = () => {
    if (editingId === null || !editText.trim()) return;
    updateQuickNote(editingId, editText, editFolder);
    closeEditor();
  };

  return (
    <div id="notes-sec" className="tsec active">
      <div className="bl">Блокнот</div>
      <textarea
        className="tnote"
        style={{ width: '100%', minHeight: 140, marginBottom: 6 }}
        value={notepad}
        onChange={(e) => { setNotepad(e.target.value); setSaveFeedback(''); }}
        placeholder="Вільні думки..."
      />
      <div className="np-meta" aria-live="polite">
        <span>{notepad.length} симв · {notepad.trim() ? notepad.trim().split(/\s+/).length : 0} слів</span>
        <span role="status">{saveFeedback}</span>
      </div>
      <button className="ab" onClick={savePad} disabled={!notepad.trim()} style={{ marginBottom: 12 }}>
        <Icon name="note" /> Зберегти як нотатку
      </button>

      <div id="folder-tabs" style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 8 }}>
        <div className={'ftab' + (noteFolder === 'all' ? ' act' : '')} onClick={() => setNoteFolder('all')}>Всі</div>
        {folders.map((f) => (
          <div className={'ftab' + (noteFolder === f.id ? ' act' : '')} key={f.id} onClick={() => setNoteFolder(f.id)}>
            {f.ico ? <>{f.ico} </> : <><Icon name="folder" /> </>}{f.nm}{' '}
            <span
              onClick={(e) => {
                e.stopPropagation();
                if (window.confirm(`Видалити папку «${f.nm}»? Задачі та нотатки лишаться (просто без цієї папки).`)) {
                  deleteFolder(f.id);
                }
              }}
              title="Видалити папку"
              style={{ fontSize: 9, color: 'var(--t3)', cursor: 'pointer', padding: '0 2px', borderRadius: 3 }}
            >
              ✕
            </span>
          </div>
        ))}
      </div>

      {addingFolder ? (
        <form className="notes-folder-form" onSubmit={saveFolder}>
          <label className="el" htmlFor="notes-folder-name">Нова папка</label>
          <input id="notes-folder-name" className="ei" autoFocus required value={folderName} onChange={(e) => setFolderName(e.target.value)} placeholder="Назва" />
          <label className="el" htmlFor="notes-folder-icon">Іконка</label>
          <input id="notes-folder-icon" className="ei" value={folderIcon} onChange={(e) => setFolderIcon(e.target.value)} maxLength={8} />
          <div className="notes-folder-actions">
            <button className="ab" type="submit" disabled={!folderName.trim()}>Додати папку</button>
            <button className="ab" type="button" onClick={() => setAddingFolder(false)}>Скасувати</button>
          </div>
        </form>
      ) : (
        <button className="ftab-add" type="button" onClick={() => setAddingFolder(true)}>+ Папка</button>
      )}

      <div style={{ display: 'flex', gap: 6, marginBottom: 10 }}>
        <input
          ref={quickNoteRef}
          id="qni"
          type="text"
          value={qnText}
          onChange={(e) => setQnText(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && send()}
          placeholder="Швидка нотатка..."
          style={{ flex: 1, minWidth: 0 }}
        />
        <select aria-label="Папка нотатки" style={{ maxWidth: '40%', minWidth: 0 }} value={qnFolder} onChange={(e) => setQnFolder(e.target.value)}>
          {folders.map((f) => (
            <option value={f.id} key={f.id}>{f.ico ? `${f.ico} ` : ''}{f.nm}</option>
          ))}
        </select>
        <button aria-label="Додати нотатку" onClick={send}>➤</button>
      </div>

      <div id="qn-list">
        {!visibleNotes.length ? (
          <div style={{ fontSize: 12, color: 'var(--t3)', padding: '6px 0' }}>Нотаток немає...</div>
        ) : (
          visibleNotes.map((n) => (
            <div className="qn-row" key={n.id}>
              {editingId === n.id ? (
                <form
                  aria-label="Редагувати нотатку"
                  style={{ width: '100%', minWidth: 0 }}
                  onSubmit={(e) => { e.preventDefault(); saveEdit(); }}
                  onKeyDown={(e) => {
                    if (e.nativeEvent.isComposing) return;
                    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                      e.preventDefault();
                      saveEdit();
                    }
                    if (e.key === 'Escape') {
                      e.preventDefault();
                      closeEditor();
                    }
                  }}
                >
                  <label className="el" htmlFor="quick-note-text">Текст нотатки</label>
                  <textarea
                    id="quick-note-text"
                    className="tnote"
                    rows={5}
                    autoFocus
                    required
                    value={editText}
                    onChange={(e) => setEditText(e.target.value)}
                    aria-describedby="quick-note-keyboard-hint"
                  />
                  <label className="el" htmlFor="quick-note-folder" style={{ marginTop: 8 }}>Папка</label>
                  <select id="quick-note-folder" className="ei" value={editFolder} onChange={(e) => setEditFolder(e.target.value)}>
                    {!folders.some((f) => f.id === editFolder) && <option value={editFolder}>Без папки</option>}
                    {folders.map((f) => <option value={f.id} key={f.id}>{f.ico ? `${f.ico} ` : ''}{f.nm}</option>)}
                  </select>
                  <p id="quick-note-keyboard-hint" style={{ fontSize: 12, color: 'var(--t2)', margin: '8px 0' }}>
                    Enter — новий рядок. Ctrl/⌘ + Enter — зберегти. Escape — скасувати.
                  </p>
                  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    <button className="ab" type="submit" disabled={!editText.trim()} style={{ minHeight: 44 }}>Зберегти нотатку</button>
                    <button className="ab" type="button" onClick={closeEditor} style={{ minHeight: 44 }}>Скасувати</button>
                  </div>
                </form>
              ) : (
                <>
                  <span className="qn-time">{n.time}</span>
                  <span className="qn-ico">{getFolderIcon(n.folder)}</span>
                  <div className="qn-txt" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', minWidth: 0 }}>{n.txt}</div>
                  <button className="qn-del" type="button" onClick={() => void copyNote(n.id, n.txt)} title="Копіювати нотатку" aria-label="Копіювати нотатку">⎘</button>
                  {copyFeedback?.id === n.id && <span className="notes-copy-feedback" role="status">{copyFeedback.message}</span>}
                  <button
                    ref={(button) => { editButtons.current[n.id] = button; }}
                    className="qn-del"
                    onClick={() => {
                      setEditingId(n.id);
                      setEditText(n.txt);
                      setEditFolder(n.folder);
                    }}
                    style={{ color: 'var(--z)', marginRight: 3 }}
                    title="Редагувати"
                    aria-label="Редагувати нотатку"
                  >
                    ✎
                  </button>
                  <button className="qn-del" aria-label="Видалити нотатку" onClick={() => deleteQuickNote(n.id)}>✕</button>
                </>
              )}
            </div>
          ))
        )}
      </div>
      {clipboardFallbackText && <ClipboardFallback text={clipboardFallbackText} onClose={() => setClipboardFallbackText(null)} />}
      <ExportImportSection />
    </div>
  );
}
