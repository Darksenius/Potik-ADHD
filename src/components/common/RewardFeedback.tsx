import { useEffect, useState } from 'react';
import { useStore } from '../../state/store';
import { titleForLevel } from '../../utils/progression';
import Modal from './Modal';
import Icon from './Icon';
import '../../styles/reward-feedback.css';

type TaskReward = { amount: number; x: number; y: number };

function rewardFromEvent(event: Event): TaskReward | null {
  const detail = (event as CustomEvent<Partial<TaskReward>>).detail;
  if (!detail || !Number.isFinite(detail.amount) || !Number.isFinite(detail.x) || !Number.isFinite(detail.y)) return null;
  return { amount: detail.amount!, x: detail.x!, y: detail.y! };
}

export default function RewardFeedback() {
  const popup = useStore(s => s.xpPopup);
  const level = useStore(s => s.levelUpFlash);
  const enabled = useStore(s => s.preferences.showGamification !== false);
  const dismissPopup = useStore(s => s.dismissXpPopup);
  const dismissLevel = useStore(s => s.dismissLevelUp);
  const reduceMotion = useStore(s => s.preferences.reduceMotion);
  const [taskReward, setTaskReward] = useState<TaskReward | null>(null);

  useEffect(() => {
    const show = (event: Event) => {
      if (useStore.getState().preferences.showGamification === false) return;
      const next = rewardFromEvent(event);
      if (!next || next.amount <= 0) return;
      setTaskReward(next);
    };
    window.addEventListener('potik:task-reward', show);
    return () => window.removeEventListener('potik:task-reward', show);
  }, []);

  useEffect(() => {
    if (!taskReward) return;
    const timer = setTimeout(() => setTaskReward(null), 1600);
    return () => clearTimeout(timer);
  }, [taskReward]);

  useEffect(() => {
    if (!popup) return;
    const timer = setTimeout(dismissPopup, 1500);
    return () => clearTimeout(timer);
  }, [popup, dismissPopup]);

  useEffect(() => {
    if (!enabled && level !== null) dismissLevel();
  }, [enabled, level, dismissLevel]);

  return <>
    {enabled && taskReward && <div className={'reward-feedback task-reward' + (reduceMotion ? ' reduced-motion' : '')} role="status" aria-live="polite"
      style={{ left: taskReward.x, top: taskReward.y }}>
      +{taskReward.amount}⚡
    </div>}
    {popup && !(taskReward && popup.tone === 'gain') && (enabled || popup.tone === 'muted') && <div className={'reward-feedback reward-' + popup.tone} role="status" aria-live="polite">
      {popup.text}
    </div>}
    {enabled && level !== null && <Modal title={'Рівень ' + level + ' · ' + titleForLevel(level)} onClose={dismissLevel}>
      <div className="level-reward"><Icon name="chart" /><p>Твої кроки накопичуються у власному темпі. Новий ранг уже твій.</p>
        <button type="button" className="secondary-action" onClick={dismissLevel}>Продовжити</button>
      </div>
    </Modal>}
  </>;
}
