import { useState, type FormEvent } from 'react';
import { useStore } from '../../state/store';
import Icon from '../common/Icon';
import '../../styles/notes-focus-parity.css';
import '../../styles/notes-workspace.css';

export default function NoteFoldersTab() {
  const folders = useStore(s => s.folders);
  const qnotes = useStore(s => s.qnotes);
  const addFolder = useStore(s => s.addFolder);
  const deleteFolder = useStore(s => s.deleteFolder);
  const setNotepadFolder = useStore(s => s.setNotepadFolder);
  const [addingFolder, setAddingFolder] = useState(false);
  const [folderName, setFolderName] = useState('');
  const [folderIcon, setFolderIcon] = useState('📁');

  function saveFolder(event: FormEvent) {
    event.preventDefault();
    const id = addFolder(folderName, folderIcon);
    if (!id) return;
    setNotepadFolder(id);
    setFolderName('');
    setFolderIcon('📁');
    setAddingFolder(false);
  }

  return <section className="tsec active notes-workspace" aria-labelledby="note-folders-title">
    <button type="button" className="secondary-action" onClick={() => useStore.getState().switchTab('more')}>← Ще</button>
    <header className="notes-page-heading">
      <h2 id="note-folders-title">Папки нотаток</h2>
      <p>Створи папки, щоб упорядкувати нотатки. Видалення папки не видаляє нотатки: вони залишаються у списку «Усі».</p>
    </header>
    <div className="notes-folder-manager">
      {!addingFolder ? (
        <button className="secondary-action" type="button" onClick={() => setAddingFolder(true)}><Icon name="plus" /> Нова папка</button>
      ) : (
        <form className="notes-folder-form" onSubmit={saveFolder}>
          <label className="el" htmlFor="notes-folder-name">Назва папки</label>
          <input id="notes-folder-name" className="ei" autoFocus required value={folderName} onChange={e => setFolderName(e.target.value)} />
          <label className="el" htmlFor="notes-folder-icon">Іконка</label>
          <input id="notes-folder-icon" className="ei" value={folderIcon} onChange={e => setFolderIcon(e.target.value)} maxLength={8} />
          <div className="notes-folder-actions">
            <button className="secondary-action" type="submit" disabled={!folderName.trim()}>Додати папку</button>
            <button className="secondary-action" type="button" onClick={() => setAddingFolder(false)}>Скасувати</button>
          </div>
        </form>
      )}
      {folders.map(folder => {
        const count = qnotes.filter(note => note.folder === folder.id).length;
        return <div className="notes-folder-manager-row" key={folder.id}>
          <span>{folder.ico} {folder.nm} <small>{count} {count === 1 ? 'нотатка' : 'нотаток'}</small></span>
          <button type="button" aria-label={'Видалити папку ' + folder.nm} title="Видалити папку" onClick={() => {
            if (window.confirm(`Видалити папку «${folder.nm}»? Нотатки залишаться у списку «Усі».`)) deleteFolder(folder.id);
          }}><Icon name="trash" /></button>
        </div>;
      })}
    </div>
  </section>;
}
