import { useEffect } from 'react';
import { useStore } from '../../state/store';

export default function Toast() {
  const message = useStore((s) => s.toastMessage);
  const dismissToast = useStore((s) => s.dismissToast);

  useEffect(() => {
    if (!message) return;
    const id = setTimeout(dismissToast, 3500);
    return () => clearTimeout(id);
  }, [message, dismissToast]);

  if (!message) return null;

  return (
    <div
      style={{
        position: 'fixed', left: '50%', bottom: 78, transform: 'translateX(-50%)',
        background: 'var(--s3)', color: 'var(--t1)', border: '1px solid var(--b2)', borderRadius: 10,
        padding: '10px 16px', fontSize: 12, fontFamily: "'Syne',sans-serif", zIndex: 400,
        maxWidth: '88%', textAlign: 'center', boxShadow: '0 6px 20px rgba(0,0,0,.3)',
      }}
    >
      {message}
    </div>
  );
}
