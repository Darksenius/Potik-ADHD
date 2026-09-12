import { useStore } from '../../state/store';

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
    <div id="nav">
      <button className={'nb' + (currentPage === 'main' ? ' act' : '')} onClick={goToTasks}>
        <span className="ni">☑</span>Потік
      </button>
      <button className={'nb' + (currentPage === 'readme' ? ' act' : '')} onClick={() => showPage('readme')}>
        <span className="ni">?</span>Довідка
      </button>
    </div>
  );
}
