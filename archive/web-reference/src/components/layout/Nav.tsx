import { useStore } from '../../state/store';
import Icon from '../common/Icon';

export default function Nav() {
  const currentPage = useStore((s) => s.currentPage);
  const showPage = useStore((s) => s.showPage);
  const switchTab = useStore((s) => s.switchTab);

  const goToTasks = () => {
    showPage('main');
    switchTab('tasks');
    // Було: setTimeout(() => tasksList.scrollIntoView(...), 120) — рядки 1280-1283.
    // Скрол-behavior можна повернути пізніше через ref на список задач, якщо знадобиться.
  };

  return (
    <nav id="nav" aria-label="Основна навігація">
      <button type="button" className={'nb' + (currentPage === 'main' ? ' act' : '')} aria-current={currentPage === 'main' ? 'page' : undefined} onClick={goToTasks}>
        <Icon name="quest" />Потік
      </button>
      <button type="button" className={'nb' + (currentPage !== 'main' ? ' act' : '')} aria-current={currentPage !== 'main' ? 'page' : undefined} onClick={() => showPage('settings')}>
        <Icon name="settings" />Налаштування
      </button>
    </nav>
  );
}
