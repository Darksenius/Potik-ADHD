import { useStore } from '../../state/store';
import { ACHIEVEMENTS } from '../../state/slices/gamificationSlice';
import { COLLECTIBLE_TOKENS, titleForLevel, tokensEarnedForActiveDays, xpForLevel } from '../../utils/progression';
import Icon from '../common/Icon';
import '../../styles/progression.css';
import '../../styles/notes-focus-parity.css';

export default function StatsTab() {
  const tasks = useStore((s) => s.tasks);
  const zonesCount = useStore((s) => s.zones.length);
  const unlocked = useStore((s) => s.unlocked);
  const progressDays = useStore((s) => s.progressDays);
  const negCount = useStore((s) => s.negCount);
  const done = useStore((s) => s.done);
  const level = useStore((s) => s.level);
  const xp = useStore((s) => s.xp);
  const xpTotal = useStore((s) => s.xpTotal);
  const rareEvents = useStore((s) => s.rareEvents);
  const addRareEvent = useStore((s) => s.addRareEvent);
  const changeRareEvent = useStore((s) => s.changeRareEvent);
  const deleteRareEvent = useStore((s) => s.deleteRareEvent);

  const activeDays = progressDays.length;
  const earnedTokens = tokensEarnedForActiveDays(activeDays);
  const tokensEarned = earnedTokens.length;
  const nextDayMilestone = activeDays >= 100 ? 100 : Math.min(100, Math.ceil((activeDays + 1) / 5) * 5);
  const nextToken = COLLECTIBLE_TOKENS[tokensEarned]?.title;
  const journeyPct = Math.min(100, Math.round((activeDays / 100) * 100));
  const xpNeeded = xpForLevel(level);
  const achievementContext = { done, xpTotal, level, zonesCount, activeDays };
  const achievementProgress = ACHIEVEMENTS.map((achievement) => ({
    achievement,
    earned: unlocked.includes(achievement.id),
    progress: achievement.progress(achievementContext),
  }));
  const earnedAchievements = achievementProgress.filter((item) => item.earned);
  const visibleAchievements = [
    ...earnedAchievements.slice(-4),
    ...achievementProgress.filter((item) => !item.earned)
      .sort((a, b) => (b.progress.current / b.progress.target) - (a.progress.current / a.progress.target))
      .slice(0, 4),
  ];
  const renderAchievement = ({ achievement, earned, progress }: (typeof achievementProgress)[number]) => (
    <article className={`achievement-card ${earned ? 'achievement-earned' : ''}`} key={achievement.id}>
      <div className="achievement-mark"><Icon name={earned ? 'check' : 'chart'} size={17} /></div>
      <div className="achievement-copy">
        <div className="achievement-title">{achievement.nm}</div>
        <div className="achievement-description">{achievement.description}</div>
        <div className="achievement-progress-copy">{earned ? 'Здобуто' : `${progress.current} / ${progress.target}`}</div>
        <div className="achievement-track" role="progressbar" aria-label={`Прогрес: ${achievement.nm}`} aria-valuemin={0} aria-valuemax={progress.target} aria-valuenow={Math.min(progress.current, progress.target)}>
          <div className="achievement-fill" style={{ width: `${Math.min(100, (progress.current / progress.target) * 100)}%` }} />
        </div>
      </div>
    </article>
  );

  return (
    <div id="stats-sec" className="tsec active">
      <section className="progression-card" aria-labelledby="journey-title">
        <div className="progression-heading">
          <div>
            <div className="progression-eyebrow">Твій шлях</div>
            <h2 id="journey-title">Рівень {level} · {titleForLevel(level)}</h2>
          </div>
          <div className="progression-total"><Icon name="chart" /> {xpTotal} XP</div>
        </div>
        <div className="progression-level-copy">{xp} / {xpNeeded} XP до наступного рівня</div>
        <div className="progression-track" role="progressbar" aria-label="Досвід до наступного рівня" aria-valuemin={0} aria-valuemax={xpNeeded} aria-valuenow={xp}>
          <div className="progression-fill" style={{ width: `${Math.min(100, (xp / xpNeeded) * 100)}%` }} />
        </div>
        <div className="journey-summary">
          <div><strong>{activeDays}<span> / 100</span></strong><span>активних днів</span></div>
          <div className="journey-note">Прогрес накопичується без серій.</div>
        </div>
        <div className="journey-note journey-activity-note">Активний день зараховується за дію: створити або завершити задачу, записати нотатку чи спланувати справу. Саме відкриття застосунку не рахується. Перерви не віднімають дні.</div>
        <div className="progression-track journey-track" role="progressbar" aria-label="Шлях зі 100 активних днів" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.min(100, activeDays)}>
          <div className="journey-fill" style={{ width: `${journeyPct}%` }} />
        </div>
        {activeDays < 100 && <div className="next-milestone">Наступна відзнака: <strong>{nextDayMilestone} активних днів</strong>{nextToken && <> · токен «{nextToken}»</>}</div>}
        {activeDays >= 100 && <div className="next-milestone"><strong>Усі 20 токенів зібрано.</strong> Сто активних днів — твій великий шлях.</div>}
      </section>

      <div className="progression-token-head"><div className="bl">Колекція токенів</div><span>{tokensEarned} / 20</span></div>
      <div className="token-grid" aria-label={`Зібрано ${tokensEarned} із 20 токенів`}>
        {COLLECTIBLE_TOKENS.map((token, index) => {
          const earned = index < tokensEarned;
          return <div className={`token ${earned ? 'token-earned' : ''}`} key={token.id} title={earned ? token.title : `Відкриється на ${token.day}-му активному дні`}>
            <span className="token-mark" aria-hidden="true">{token.day}</span>
            <span className="token-name">{earned ? token.title : '???'}</span>
            <span className="token-unlock">{earned ? 'зібрано' : `${token.day} днів`}</span>
          </div>;
        })}
      </div>

      <div className="bl progression-section-title">Статистика</div>
      <div className="streak-c">
        <div className="stats-streak-icon" aria-hidden="true"><Icon name="calendar" size={28} /></div>
        <div><div className="stats-streak-value">{activeDays}</div><div className="stats-streak-label">Активні дні · не серія</div></div>
        <div className="stats-harmful"><div>Шкідливих</div><strong>{negCount}</strong></div>
      </div>
      <div id="sg">
        <div className="sc2"><div className="sv">{done}</div><div className="sl">Виконано</div></div>
        <div className="sc2"><div className="sv">{tasks.filter((t) => !t.trashed && !t.someday).length}</div><div className="sl">Задач</div></div>
      </div>

      <div className="progression-token-head progression-section-title"><div className="bl">Відзнаки</div><span>{earnedAchievements.length} здобуто</span></div>
      <div className="achievement-list">
        {visibleAchievements.map(renderAchievement)}
      </div>
      <details className="all-achievements">
        <summary>Усі {ACHIEVEMENTS.length} відзнак</summary>
        <div className="achievement-list all-achievement-list">
          {achievementProgress.map(renderAchievement)}
        </div>
      </details>

      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
        <div className="bl" style={{ margin: 0 }}>Рідкісні події</div>
        <button className="ab" onClick={() => {
          const nm = window.prompt('Назва події (наприклад: Зайві витрати, Борг):');
          if (!nm || !nm.trim()) return;
          const dir = window.confirm('Лічильник зменшується? (ОК = так, Скасувати = зростає)') ? 'down' : 'up';
          addRareEvent(nm, dir);
        }}>+ Подія</button>
      </div>
      <div id="rare-list">
        {!rareEvents.length ? (
          <div style={{ fontSize: 11, color: 'var(--t3)', textAlign: 'center', padding: 10 }}>Немає подій — натисни +</div>
        ) : rareEvents.map((r) => (
          <div key={r.id} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '7px 0', borderBottom: '1px solid var(--b1)' }}>
            <span style={{ flex: 1, fontSize: 13 }}>{r.nm}</span>
            <button className="rc-btn" onClick={() => changeRareEvent(r.id, -1)} style={{ background: 'none', border: '1px solid var(--b2)', borderRadius: 6, padding: '2px 8px', cursor: 'pointer', fontSize: 14, color: 'var(--t2)' }}>−</button>
            <span style={{ minWidth: 28, textAlign: 'center', fontSize: 15, fontWeight: 700, color: 'var(--z)' }}>{r.val}</span>
            <button className="rc-btn" onClick={() => changeRareEvent(r.id, 1)} style={{ background: 'none', border: '1px solid var(--b2)', borderRadius: 6, padding: '2px 8px', cursor: 'pointer', fontSize: 14, color: 'var(--t2)' }}>+</button>
            <span style={{ fontSize: 10, color: 'var(--t3)' }}>{r.dir === 'down' ? '↓' : '↑'}</span>
            <button className="ab" onClick={() => deleteRareEvent(r.id)} style={{ color: 'var(--t3)' }}>✕</button>
          </div>
        ))}
      </div>
    </div>
  );
}
