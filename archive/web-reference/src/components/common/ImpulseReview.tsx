import { useEffect, useState } from 'react';
import { useStore } from '../../state/store';
import '../../styles/reward-feedback.css';

/** Restore the five-minute in-app nudge for captured impulses. */
export default function ImpulseReview() {
  const reviews = useStore(s => s.noteReviews);
  const notes = useStore(s => s.qnotes);
  const dismiss = useStore(s => s.dismissNoteReviews);
  const [now, setNow] = useState(Date.now());
  const valid = reviews.filter(review => notes.some(note => note.id === review.noteId && note.folder === 'impulse'));
  const next = valid.filter(review => review.dueAt > now).reduce((at, review) => Math.min(at, review.dueAt), Infinity);
  const due = valid.filter(review => review.dueAt <= now);

  useEffect(() => {
    if (!Number.isFinite(next)) return;
    const timer = setTimeout(() => setNow(Date.now()), Math.max(1, next - Date.now()));
    return () => clearTimeout(timer);
  }, [next]);

  useEffect(() => {
    const refresh = () => setNow(Date.now());
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', refresh);
    return () => {
      window.removeEventListener('focus', refresh);
      document.removeEventListener('visibilitychange', refresh);
    };
  }, []);

  if (!due.length) return null;
  const note = notes.find(item => item.id === due[0].noteId)!;
  const close = () => dismiss(due.map(review => review.noteId));
  return <aside className="impulse-review" role="status">
    <p><strong>Розібрати швидку нотатку?</strong><span>{note.txt.slice(0, 100)}{note.txt.length > 100 ? '…' : ''}{due.length > 1 ? ` · ще ${due.length - 1}` : ''}</span></p>
    <div><button type="button" onClick={() => {
      const state = useStore.getState();
      state.setNoteFolder('impulse'); state.showPage('main'); state.switchTab('notes'); close();
    }}>Відкрити нотатки</button><button type="button" onClick={close}>Закрити</button></div>
  </aside>;
}
