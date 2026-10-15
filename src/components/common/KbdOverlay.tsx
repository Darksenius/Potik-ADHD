import { useState, useEffect, useRef } from 'react';
import { useStore } from '../../state/store';

export default function KbdOverlay() {
  const kbdOverlay = useStore((s) => s.kbdOverlay);
  const closeKbdOverlay = useStore((s) => s.closeKbdOverlay);
  const folders = useStore((s) => s.folders);

  const [text, setText] = useState('');
  const [selectedFolder, setSelectedFolder] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (kbdOverlay) {
      const initialVal = kbdOverlay.initialValue || '';
      setText(initialVal);
      setSelectedFolder(kbdOverlay.folderSel || (folders[0]?.id ?? ''));

      const timer = setTimeout(() => {
        if (textareaRef.current) {
          textareaRef.current.focus();
          if (initialVal) {
            try {
              textareaRef.current.setSelectionRange(initialVal.length, initialVal.length);
            } catch {
              // Ignore selection errors on non-supporting inputs
            }
          }
        }
      }, 80);
      return () => clearTimeout(timer);
    } else {
      setText('');
      setSelectedFolder('');
    }
  }, [kbdOverlay, folders]);

  const handleSend = () => {
    const trimmed = text.trim();
    if (!trimmed || !kbdOverlay?.onSend) {
      closeKbdOverlay();
      return;
    }
    kbdOverlay.onSend(trimmed, kbdOverlay.folderSel ? selectedFolder : null);
    closeKbdOverlay();
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleCancel = () => {
    closeKbdOverlay();
  };

  return (
    <div id="kbd-overlay" className={kbdOverlay ? 'show' : ''}>
      {kbdOverlay && (
        <>
          <div className="kbd-hint" id="kbd-hint-row">
            <span id="kbd-hint-txt">{kbdOverlay.hint || '⚡ Нотатка'}</span>
            {kbdOverlay.showTaskHint && text.startsWith('...') && (
              <span className="kbd-hint-tag" id="kbd-task-hint">
                ... на початку → створить задачу
              </span>
            )}
            {kbdOverlay.folderSel && (
              <select
                id="kbd-folder"
                value={selectedFolder}
                onChange={(e) => setSelectedFolder(e.target.value)}
                style={{
                  marginLeft: 'auto',
                  background: 'var(--s3)',
                  border: '1px solid var(--b2)',
                  borderRadius: 7,
                  padding: '4px 7px',
                  color: 'var(--t1)',
                  fontFamily: "'Syne',sans-serif",
                  fontSize: 11,
                  outline: 'none',
                  cursor: 'pointer',
                }}
              >
                {folders.map((f) => (
                  <option value={f.id} key={f.id}>
                    {(f.ico ? f.ico + ' ' : '') + f.nm}
                  </option>
                ))}
              </select>
            )}
          </div>
          <div className="kbd-row">
            <button className="kbd-cancel" onClick={handleCancel}>
              ✕
            </button>
            <textarea
              ref={textareaRef}
              id="kbd-txt"
              rows={3}
              placeholder={kbdOverlay.placeholder || 'Пиши тут...'}
              value={text}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={handleKeyDown}
            />
            <button className="kbd-send" onClick={handleSend}>
              →
            </button>
          </div>
        </>
      )}
    </div>
  );
}
