import { useEffect } from 'react';
import { useStore } from '../../state/store';
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
    {enabled && level !== null && <Modal title={'Рівень ' + level + '!'} onClose={dismissLevel}>
      <div className="level-reward"><Icon name="chart" /><p>Твої дії накопичуються. Новий рівень уже твій.</p>
        <button type="button" className="secondary-action" onClick={dismissLevel}>Продовжити</button>
      </div>
    </Modal>}
  </>;
}
