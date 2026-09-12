import { useState } from 'react';
import { useStore } from '../../state/store';
import ExportImportSection from './ExportImportSection';

export default function NotesTab() {
  const notepad = useStore((s) => s.notepad);
  const setNotepad = useStore((s) => s.setNotepad);
  const saveNotepadAsNote = useStore((s) => s.saveNotepadAsNote);
  const folders = useStore((s) => s.folders);
  const qnotes = useStore((s) => s.qnotes);
  const noteFolder = useStore((s) => s.noteFolder);
  const setNoteFolder = useStore((s) => s.setNoteFolder);
  const addQuickNote = useStore((s) => s.addQuickNote);
  const updateQuickNote = useStore((s) => s.updateQuickNote);
  const deleteQuickNote = useStore((s) => s.deleteQuickNote);
  const deleteFolder = useStore((s) => s.deleteFolder);
  const getFolderIcon = useStore((s) => s.getFolderIcon);

  const [qnText, setQnText] = useState('');
  const [qnFolder, setQnFolder] = useState('impulse');
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editText, setEditText] = useState('');

  const visibleNotes = noteFolder === 'all' ? qnotes : qnotes.filter((n) => n.folder === noteFolder);

  const send = () => {
    const t = qnText.trim();
    if (!t) return;
    addQuickNote(t, qnFolder);
    setQnText('');
  };

  return (
    <div id="notes-sec" className="tsec active">
      <div className="bl">Блокнот</div>
      <textarea
        className="tnote"
        style={{ width: '100%', minHeight: 80, marginBottom: 6 }}
        value={notepad}
        onChange={(e) => setNotepad(e.target.value)}
        placeholder="Вільні думки..."
      />
      <button className="ab" onClick={saveNotepadAsNote} style={{ marginBottom: 12 }}>
        📌 Зберегти як нотатку
      </button>

      <div id="folder-tabs" style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 8 }}>
        <div className={'ftab' + (noteFolder === 'all' ? ' act' : '')} onClick={() => setNoteFolder('all')}>Всі</div>
        {folders.map((f) => (
          <div className={'ftab' + (noteFolder === f.id ? ' act' : '')} key={f.id} onClick={() => setNoteFolder(f.id)}>
            {f.ico || '📁'} {f.nm}{' '}
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

      <div style={{ display: 'flex', gap: 6, marginBottom: 10 }}>
        <input
          id="qni"
          type="text"
          value={qnText}
          onChange={(e) => setQnText(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && send()}
          placeholder="Швидка нотатка..."
          style={{ flex: 1 }}
        />
        <select value={qnFolder} onChange={(e) => setQnFolder(e.target.value)}>
          {folders.map((f) => (
            <option value={f.id} key={f.id}>{f.ico || '📁'} {f.nm}</option>
          ))}
        </select>
        <button onClick={send}>➤</button>
      </div>

      <div id="qn-list">
        {!visibleNotes.length ? (
          <div style={{ fontSize: 12, color: 'var(--t3)', padding: '6px 0' }}>Нотаток немає...</div>
        ) : (
          visibleNotes.map((n) => (
            <div className="qn-row" key={n.id}>
              <span className="qn-time">{n.time}</span>
              <span className="qn-ico">{getFolderIcon(n.folder)}</span>
              {editingId === n.id ? (
                <input
                  className="qn-txt"
                  autoFocus
                  value={editText}
                  onChange={(e) => setEditText(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      updateQuickNote(n.id, editText);
                      setEditingId(null);
                    }
                    if (e.key === 'Escape') setEditingId(null);
                  }}
                  onBlur={() => {
                    if (editText.trim()) updateQuickNote(n.id, editText);
                    setEditingId(null);
                  }}
                />
              ) : (
                <div className="qn-txt">{n.txt}</div>
              )}
              <button
                className="qn-del"
                onClick={() => {
                  setEditingId(n.id);
                  setEditText(n.txt);
                }}
                style={{ color: 'var(--z)', marginRight: 3 }}
                title="Редагувати"
              >
                ✎
              </button>
              <button className="qn-del" onClick={() => deleteQuickNote(n.id)}>✕</button>
            </div>
          ))
        )}
      </div>
      <ExportImportSection />
    </div>
  );
}
