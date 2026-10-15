import { useSyncExternalStore } from 'react';
import { getTaskUndoNotice, subscribeTaskUndo, undoTaskAction } from '../../services/taskUndo';

export default function TaskUndoNotice() {
  const notice = useSyncExternalStore(subscribeTaskUndo, getTaskUndoNotice, getTaskUndoNotice);
  if (!notice) return null;
  return <div className="task-undo-notice" role="status"><span>{notice.message}</span><button type="button" onClick={undoTaskAction}>Скасувати</button></div>;
}
