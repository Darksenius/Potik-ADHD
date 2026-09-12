import { useState } from 'react';
import { useStore } from '../../state/store';
import type { WeekTemplate } from '../../types';
import { WD, fmtDate, fmtHuman, addDaysDs } from '../../utils/date';
import { WKC_SPANS } from '../../constants';

function dayName(dayTemplates: { id: string; name: string }[], e: { tplId?: string; rest?: boolean } | null): string {
  if (!e || !e.tplId) return e && e.rest ? 'Вихідний' : 'без зон';
  const t = dayTemplates.find((x) => x.id === e.tplId);
  return t ? t.name : '?';
}

export default function WeekConstructor() {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<WeekTemplate | null>(null);
  const [applyId, setApplyId] = useState<string | null>(null);
  const [applyStart, setApplyStart] = useState(fmtDate(new Date()));
  const [applySpan, setApplySpan] = useState(WKC_SPANS[1][0]);

  const weekTemplates = useStore((s) => s.weekTemplates);
  const dayTemplates = useStore((s) => s.dayTemplates);
  const planRules = useStore((s) => s.planRules);
  const planDayZones = useStore((s) => s.planDayZones);
  const planRestDays = useStore((s) => s.planRestDays);
  const planDayOff = useStore((s) => s.planDayOff);
  const saveWeekTemplate = useStore((s) => s.saveWeekTemplate);
  const deleteWeekTemplate = useStore((s) => s.deleteWeekTemplate);
  const applyWeekTemplate = useStore((s) => s.applyWeekTemplate);
  const deleteRule = useStore((s) => s.deleteRule);

  const startNew = () => {
    setApplyId(null);
    setDraft({ id: '', name: '', days: [{ tplId: 'tplWork', rest: false }] });
  };
  const startEdit = (id: string) => {
    const tp = weekTemplates.find((x) => x.id === id);
    if (!tp) return;
    setApplyId(null);
    setDraft(JSON.parse(JSON.stringify(tp)));
  };
  const handleDelete = (id: string) => {
    const tp = weekTemplates.find((x) => x.id === id);
    if (!tp) return;
    if (!window.confirm(`Видалити графік «${tp.name}»? Уже застосовані дні залишаться.`)) return;
    deleteWeekTemplate(id);
    if (draft?.id === id) setDraft(null);
    if (applyId === id) setApplyId(null);
  };
  const handleSave = () => {
    if (!draft) return;
    const name = draft.name.trim();
    if (!name) {
      window.alert('Дай графіку назву (напр. «5/2»).');
      return;
    }
    saveWeekTemplate({ ...draft, name });
    setDraft(null);
  };
  const handleApply = () => {
    if (!applyId) return;
    const tp = weekTemplates.find((x) => x.id === applyId);
    if (!tp) return;
    const endDs = addDaysDs(applyStart, applySpan - 1);
    let manual = 0;
    for (let i = 0; i < applySpan; i++) {
      const ds = addDaysDs(applyStart, i);
      if (planDayZones[ds] || planRestDays[ds] !== undefined || planDayOff[ds]) manual++;
    }
    const msg =
      `Застосувати «${tp.name}» з ${fmtHuman(new Date(applyStart + 'T12:00:00'))} по ${fmtHuman(new Date(endDs + 'T12:00:00'))} (${applySpan} дн.)?` +
      (manual ? `\nРучні правки на ${manual} дн. буде стерто.` : '');
    if (!window.confirm(msg)) return;
    applyWeekTemplate(applyId, applyStart, applySpan);
    setApplyId(null);
  };
  const handleRuleDel = (i: number) => {
    const r = planRules[i];
    if (!r) return;
    if (!window.confirm(`Прибрати графік «${r.name}» (${r.start} → ${r.end})? Дні цього періоду стануть нерозміченими (ручні правки днів залишаться).`)) return;
    deleteRule(i);
  };

  return (
    <div id="wkc">
      <div className="wkc-head" onClick={() => setOpen(!open)}>
        <span className="wkc-title">🗓 Конструктор тижня</span>
        <span className={'wkc-arrow' + (open ? ' open' : '')}>▼</span>
      </div>
      {open && (
        <div className="wkc-body open">
          <div style={{ fontSize: 10, color: 'var(--t3)', marginBottom: 5 }}>
            Обери графік і застосуй на період — зони ляжуть по днях самі. ▶ — застосувати.
          </div>

          {weekTemplates.length ? (
            weekTemplates.map((tp) => (
              <div className="wkc-row" key={tp.id}>
                <span className="wkc-nm">{tp.name}</span>
                <div className="wkc-strip">
                  {tp.days.map((d, i) => (
                    <span className={'wkc-cell ' + (d.rest ? 'r' : 'w')} title={dayName(dayTemplates, d)} key={i}>
                      {d.rest ? 'В' : 'Р'}
                    </span>
                  ))}
                </div>
                <button
                  className="wkc-btn go"
                  onClick={() => {
                    setDraft(null);
                    setApplyId(applyId === tp.id ? null : tp.id);
                  }}
                >
                  ▶
                </button>
                <button className="wkc-btn" onClick={() => startEdit(tp.id)}>✎</button>
                <button className="wkc-btn" onClick={() => handleDelete(tp.id)}>✕</button>
              </div>
            ))
          ) : (
            <div style={{ fontSize: 11, color: 'var(--t3)', padding: '4px 0' }}>Ще немає графіків — створи перший</div>
          )}

          {!!planRules.length && (
            <>
              <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--t3)', textTransform: 'uppercase', letterSpacing: '.05em', margin: '9px 0 3px' }}>
                ✓ Застосовано
              </div>
              {planRules.map((r, i) => (
                <div className="wkc-row" key={r.id}>
                  <span className="wkc-nm">{r.name}</span>
                  <span style={{ fontSize: 10, color: 'var(--t2)', flex: 1 }}>
                    {fmtHuman(new Date(r.start + 'T12:00:00'))} → {fmtHuman(new Date(r.end + 'T12:00:00'))}
                  </span>
                  <button className="wkc-btn" onClick={() => handleRuleDel(i)}>✕</button>
                </div>
              ))}
            </>
          )}

          {applyId && (() => {
            const tp = weekTemplates.find((x) => x.id === applyId);
            if (!tp) return null;
            return (
              <div className="wkc-panel">
                <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--t3)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 6 }}>
                  ▶ Застосувати «{tp.name}»
                </div>
                <div style={{ display: 'flex', gap: 5, alignItems: 'center', flexWrap: 'wrap' }}>
                  <span style={{ fontSize: 11, color: 'var(--t2)' }}>з</span>
                  <input
                    type="date"
                    value={applyStart}
                    onChange={(e) => setApplyStart(e.target.value)}
                    style={{ background: 'var(--s3)', border: '1px solid var(--b2)', borderRadius: 6, color: 'var(--t1)', fontSize: 11, padding: '4px 5px' }}
                  />
                  <span style={{ fontSize: 11, color: 'var(--t2)' }}>на</span>
                  <select className="wkc-sel" style={{ flex: 'none' }} value={applySpan} onChange={(e) => setApplySpan(Number(e.target.value))}>
                    {WKC_SPANS.map(([days, label]) => (
                      <option value={days} key={days}>{label}</option>
                    ))}
                  </select>
                  <button className="wkc-btn go" onClick={handleApply}>Застосувати</button>
                </div>
                {tp.days.length === 7 && (
                  <div style={{ fontSize: 10, color: 'var(--t3)', marginTop: 5 }}>
                    Графік на 7 днів — кожен день стане на свій Пн–Нд автоматично.
                  </div>
                )}
              </div>
            );
          })()}

          {draft && (
            <div className="wkc-panel">
              <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--t3)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 6 }}>
                {draft.id ? '✎ Редагування графіка' : '＋ Новий графік'}
              </div>
              <input
                type="text"
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                placeholder="Назва (напр. 5/2)"
                style={{ width: '100%', boxSizing: 'border-box', background: 'var(--s3)', border: '1px solid var(--b2)', borderRadius: 6, color: 'var(--t1)', fontSize: 12, padding: '6px 8px', fontFamily: "'Syne',sans-serif", marginBottom: 7, outline: 'none' }}
              />
              {draft.days.map((d, i) => (
                <div className="wkc-day-row" key={i}>
                  <span className="wkc-day-lbl">{draft.days.length === 7 ? WD[i] : 'Д' + (i + 1)}</span>
                  <select
                    className="wkc-sel"
                    value={d.tplId || ''}
                    onChange={(e) => {
                      const v = e.target.value;
                      const days = draft.days.slice();
                      days[i] = { ...days[i], tplId: v, rest: v === 'tplRest' ? true : days[i].rest };
                      setDraft({ ...draft, days });
                    }}
                  >
                    <option value="">— без зон —</option>
                    {dayTemplates.map((t) => (
                      <option value={t.id} key={t.id}>{t.name}</option>
                    ))}
                  </select>
                  <button
                    className={'wkc-moon' + (d.rest ? ' on' : '')}
                    title="Вихідний день"
                    onClick={() => {
                      const days = draft.days.slice();
                      days[i] = { ...days[i], rest: !days[i].rest };
                      setDraft({ ...draft, days });
                    }}
                  >
                    🌙
                  </button>
                  {draft.days.length > 1 && (
                    <button
                      className="plan-rm"
                      onClick={() => {
                        const days = draft.days.slice();
                        days.splice(i, 1);
                        setDraft({ ...draft, days });
                      }}
                    >
                      ✕
                    </button>
                  )}
                </div>
              ))}
              <div style={{ display: 'flex', gap: 5, marginTop: 6, flexWrap: 'wrap' }}>
                <button className="wkc-btn" onClick={() => setDraft({ ...draft, days: [...draft.days, { tplId: 'tplWork', rest: false }] })}>
                  + День
                </button>
                <span style={{ flex: 1 }} />
                <button className="wkc-btn" onClick={() => setDraft(null)}>Скасувати</button>
                <button className="wkc-btn go" onClick={handleSave}>💾 Зберегти</button>
              </div>
              <div style={{ fontSize: 10, color: 'var(--t3)', marginTop: 6 }}>
                7 днів — прив&apos;язка до Пн–Нд; інша довжина — цикл від дати старту. 🌙 = вихідний.
              </div>
            </div>
          )}

          {!draft && (
            <button className="pdd-add" style={{ marginTop: 8 }} onClick={startNew}>
              + Новий графік
            </button>
          )}
        </div>
      )}
    </div>
  );
}
