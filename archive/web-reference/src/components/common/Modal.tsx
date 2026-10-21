import type { ReactNode } from 'react';

export default function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <div
      className="modal show"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="modal-box">
        <h4>
          {title}
          <button
            onClick={onClose}
            style={{ float: 'right', background: 'none', border: 'none', color: 'var(--t3)', cursor: 'pointer', fontSize: 14 }}
          >
            ✕
          </button>
        </h4>
        {children}
      </div>
    </div>
  );
}
