import type { AppSlice } from '../store';
import type { Folder, QuickNote } from '../../types';
import { fmtDate } from '../../utils/date';

/** Перенесено 1:1 з www/index.html, рядки 1192–1197 (сідові дані). */
const SEED_FOLDERS: Folder[] = [
  { id: 'impulse', nm: 'Імпульс', ico: '⚡' },
  { id: 'thought', nm: 'Думка', ico: '💭' },
  { id: 'general', nm: 'Загальне', ico: '📝' },
  { id: 'therapy', nm: 'Терапія', ico: '💆' },
  { id: 'training', nm: 'Тренування', ico: '💪' },
  { id: 'food', nm: 'Харчування', ico: '🍎' },
  { id: 'work', nm: 'Робота', ico: '💼' },
  { id: 'kid', nm: 'Дитина', ico: '👶' },
];

/** FOLDER_ICO — рядок 1255, фолбек-іконки для вбудованих папок */
const FOLDER_ICO: Record<string, string> = {
  impulse: '⚡', thought: '💭', general: '📝', therapy: '💆', training: '💪', food: '🍎', work: '💼', kid: '👶',
};

export interface NotesSlice {
  qnotes: QuickNote[];
  folders: Folder[];
  notepad: string;
  /** Наступний вільний id для нотаток (було глобальне `qnid`, стартувало зі 100) */
  qnid: number;

  /** mkNote() + unshift — рядки 1257–1263, 4417, doAddQN рядок 2412 */
  addQuickNote: (text: string, folder?: string) => QuickNote;
  /** editQN(id) — рядки 2446–2458 (без kbdOpen — текст приходить від компонента) */
  updateQuickNote: (id: number, text: string, folder?: string) => void;
  /** delQN(id) — рядок 2431 */
  deleteQuickNote: (id: number) => void;
  /** getFIco(fid) — рядок 2432 */
  getFolderIcon: (folderId: string) => string;

  /** setNotepad — частина npMeta(), рядок 2313 (лише дані, лічильники символів/слів — робота компонента) */
  setNotepad: (text: string) => void;
  /** npSave() — рядки 2314–2330, без DOM-анімації (те — робота компонента) */
  saveNotepadAsNote: () => void;

  /** doSaveFolder()/efAddFolder() — рядки 2354–2362, 2384–2392 */
  addFolder: (name: string, icon?: string) => string;
  /**
   * delFolder(id) — рядки 2364–2375, без confirm() (питає компонент).
   * Видаляє папку, але НЕ задачі/нотатки — лише прибирає прив'язку
   * (nотатки → 'general', задачі → null).
   */
  deleteFolder: (id: string) => void;
}

export const createNotesSlice: AppSlice<NotesSlice> = (set, get) => ({
  qnotes: [],
  folders: SEED_FOLDERS,
  notepad: '',
  qnid: 100,

  addQuickNote: (text, folder = 'impulse') => {
    const now = new Date();
    const note: QuickNote = {
      id: get().qnid,
      txt: text,
      folder,
      time: now.toLocaleTimeString('uk', { hour: '2-digit', minute: '2-digit' }),
      date: fmtDate(now),
    };
    set((s) => ({ qnotes: [note, ...s.qnotes], qnid: s.qnid + 1 }));
    return note;
  },

  updateQuickNote: (id, text, folder) => {
    const trimmed = text.trim();
    if (!trimmed) return;
    set((s) => ({
      qnotes: s.qnotes.map((n) => (n.id === id ? { ...n, txt: trimmed, folder: folder || n.folder } : n)),
    }));
  },

  deleteQuickNote: (id) => {
    set((s) => ({ qnotes: s.qnotes.filter((n) => n.id !== id) }));
  },

  getFolderIcon: (folderId) => {
    const f = get().folders.find((x) => x.id === folderId);
    return FOLDER_ICO[folderId] || (f ? f.ico : '📝');
  },

  setNotepad: (text) => set({ notepad: text }),

  saveNotepadAsNote: () => {
    const v = get().notepad.trim();
    if (!v) return;
    get().addQuickNote(v, 'thought');
    set({ notepad: '' });
  },

  addFolder: (name, icon) => {
    const nm = name.trim();
    if (!nm) return '';
    const id = 'f' + Date.now();
    const folder: Folder = { id, nm, ico: (icon || '📁').trim() || '📁' };
    set((s) => ({ folders: [...s.folders, folder] }));
    return id;
  },

  deleteFolder: (id) => {
    set((s) => ({
      folders: s.folders.filter((f) => f.id !== id),
      qnotes: s.qnotes.map((n) => (n.folder === id ? { ...n, folder: 'general' } : n)),
      tasks: s.tasks.map((t) => (t.folderId === id ? { ...t, folderId: null } : t)),
      noteFolder: s.noteFolder === id ? 'all' : s.noteFolder,
      taskFilter: s.taskFilter === 'f:' + id ? 'all' : s.taskFilter,
    }));
  },
});
