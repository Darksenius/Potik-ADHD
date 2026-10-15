import { useEffect } from 'react';
import { useStore } from '../../state/store';
import { titleForLevel } from '../../utils/progression';
import Modal from './Modal';
import Icon from './Icon';
import '../../styles/reward-feedback.css';

export default function RewardFeedback() {
  const popup = useStore(s => s.xpPopup);
  const level = useStore(s => s.levelUpFlash);
  const enabled = useStore(s => s.preferences.showGamification !== false);
  const dismissPopup = useStore(s => s.dismissXpPopup);
  const dismissLevel = useStore(s => s.dismissLevelUp);

  useEffect(() => {
    if (!popup) return;
    const timer = setTimeout(dismissPopup, 1500);
    return () => clearTimeout(timer);
  }, [popup, dismissPopup]);

  useEffect(() => {
    if (!enabled && level !== null) dismissLevel();
  }, [enabled, level, dismissLevel]);

  return <>
    {popup && (enabled || popup.tone === 'muted') && <div className={'reward-feedback reward-' + popup.tone} role="status" aria-live="polite">
      {popup.text}
    </div>}
    {enabled && level !== null && <Modal title={'Рівень ' + level + ' · ' + titleForLevel(level)} onClose={dismissLevel}>
      <div className="level-reward"><Icon name="chart" /><p>Твої кроки накопичуються у власному темпі. Новий ранг уже твій.</p>
        <button type="button" className="secondary-action" onClick={dismissLevel}>Продовжити</button>
      </div>
    </Modal>}
  </>;
}
