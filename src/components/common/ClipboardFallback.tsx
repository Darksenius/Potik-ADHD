export default function ClipboardFallback({ text, onClose }: { text: string; onClose: () => void }) {
  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', zIndex: 400, display: 'flex', flexDirection: 'column', padding: 18, gap: 10 }}>
      <div style={{ color: '#fff', fontSize: 13, fontWeight: 700, fontFamily: "'Syne',sans-serif" }}>Скопіюй текст і встав у ШІ:</div>
      <textarea
        style={{ flex: 1, width: '100%', background: 'var(--s1)', color: 'var(--t1)', border: '1px solid var(--b2)', borderRadius: 10, padding: 10, fontSize: 12, fontFamily: 'monospace' }}
        defaultValue={text}
        autoFocus
        onFocus={(e) => e.currentTarget.select()}
        readOnly
      />
      <button
        onClick={onClose}
        style={{ background: 'var(--z)', border: 'none', borderRadius: 8, padding: 11, color: '#fff', fontFamily: "'Syne',sans-serif", fontWeight: 700, cursor: 'pointer' }}
      >
        Закрити
      </button>
    </div>
  );
}
