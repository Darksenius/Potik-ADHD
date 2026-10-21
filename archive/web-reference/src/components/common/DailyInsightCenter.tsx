import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { useStore } from '../../state/store';
import { getSaveStatus, subscribeSaveStatus } from '../../services/saveStatus';
import { insightForClaim, INSIGHT_SOURCES } from '../../utils/dailyInsights';
import { fmtDate } from '../../utils/date';
import { updateForegroundService } from '../../bridge/nativeBridge';
import '../../styles/daily-insights.css';

export default function DailyInsightCenter() {
  const claims = useStore(state => state.dailyInsightClaims);
  const notice = useStore(state => state.dailyInsightNotice);
  const selected = useStore(state => state.selectedInsightDay);
  const open = useStore(state => state.openDailyInsight);
  const close = useStore(state => state.closeDailyInsight);
  const dismiss = useStore(state => state.dismissDailyInsightNotice);
  const status = useSyncExternalStore(subscribeSaveStatus, getSaveStatus, getSaveStatus);
  const enabled = useStore(state => state.preferences.showGamification !== false);
  const [committedNotice, setCommittedNotice] = useState<string | null>(null);
  const refreshedNotice = useRef<string | null>(null);
  const dialog = useRef<HTMLDivElement>(null);
  const claim = claims.find(item => item.day === selected);
  const card = claim && insightForClaim(claim);
  const noticeClaim = claims.find(item => item.day === notice);
  const noticeCard = noticeClaim && insightForClaim(noticeClaim);
  useEffect(() => {
    if (!notice) { setCommittedNotice(null); return; }
    if (status.phase !== 'saved' || refreshedNotice.current === notice) return;
    refreshedNotice.current = notice;
    setCommittedNotice(notice);
    // Refresh once after the claim is durable, rather than waiting for the minute tick.
    updateForegroundService();
  }, [notice, status.phase]);
  useEffect(() => {
    if (!committedNotice) return;
    const timer = setTimeout(dismiss, 6000);
    return () => clearTimeout(timer);
  }, [committedNotice, dismiss]);
  useEffect(() => {
    if (!selected) return;
    const previous = document.activeElement as HTMLElement | null;
    dialog.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); close(); }
      if (event.key !== 'Tab') return;
      const elements = dialog.current?.querySelectorAll<HTMLElement>('button:not([disabled]),a[href]');
      if (!elements?.length) return;
      const first = elements[0], last = elements[elements.length - 1];
      if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.current)) {
        event.preventDefault(); last.focus();
      } else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('keydown', onKey); if (previous?.isConnected) previous.focus(); };
  }, [selected, close]);
  return <>
    {enabled && notice && committedNotice === notice && noticeCard && !selected && <aside className="insight-notice" role="status">
      <button type="button" onClick={() => open(notice)}><strong>Активний день {noticeClaim!.sequence} ✦</strong><span>{noticeCard.title}</span></button>
      <button type="button" aria-label="Приховати думку дня" onClick={dismiss}>×</button>
    </aside>}
    {selected && <div className="insight-dialog-backdrop" onClick={event => { if (event.target === event.currentTarget) close(); }}>
      <div ref={dialog} className="insight-dialog" role="dialog" aria-modal="true" aria-labelledby="daily-insight-title" tabIndex={-1}>
        <div className="insight-dialog-top"><span>{new Date(selected + 'T12:00:00').toLocaleDateString('uk', { day: 'numeric', month: 'long', year: 'numeric' })}</span>
          <button type="button" aria-label="Закрити думку дня" onClick={close}>×</button></div>
        {card && claim ? <>
          <div className="insight-eyebrow">Думка {claim.cardId} зі 100 · активний день {claim.sequence}{claim.sequence > 100 ? ` · коло ${Math.ceil(claim.sequence / 100)}` : ''}</div>
          <h2 id="daily-insight-title">{card.title}</h2><p className="insight-text">{card.text}</p>
          {!!card.sources.length && <details className="insight-sources"><summary>Джерела й пояснення</summary>
            {card.sources.map(id => { const source = INSIGHT_SOURCES.find(item => item.id === id);
              return source ? <a key={id} href={source.url} target="_blank" rel="noopener noreferrer">{source.title}</a> : null; })}
            <p>Це загальні орієнтири для щоденних рішень. З індивідуальними питаннями здоров’я звертайся до фахівця.</p>
          </details>}
        </> : <><h2 id="daily-insight-title">{selected === fmtDate(new Date()) ? 'Думка ще попереду' : 'День без відкритої думки'}</h2>
          <p className="insight-text">{selected === fmtDate(new Date()) ? 'Створи чи заверши задачу, запиши нотатку або щось сплануй — і відкриється думка цього активного дня.' : 'Цього дня нагороди не було. Пропуски не забирають відкриті думки й не скидають твій шлях.'}</p></>}
        <button type="button" className="insight-close-button" onClick={close}>Гаразд</button>
      </div>
    </div>}
  </>;
}
