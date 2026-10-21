import { useStore } from '../../state/store';
import { useAppVersion } from '../oss/OssPage';
import releaseNotes from '../../content/release-notes.json';
import '../../styles/whats-new.css';

/** Personal release explanations travel inside the APK, including older entries. */
export default function WhatsNewPage() {
  const showPage = useStore(s => s.showPage);
  const version = useAppVersion();
  return <main id="whats-new-page" className="page active whats-new-page">
    <header className="whats-new-header">
      <button type="button" className="secondary-action" onClick={() => showPage('settings')}>Назад до налаштувань</button>
      <h1>Що нового</h1>
      <p>Твоя версія: {version}</p>
    </header>
    <p className="whats-new-intro">Що я змінила в поточній та попередніх версіях.</p>
    {releaseNotes.map((note, index) => <details className="release-note" data-version={note.version} open={index === 0} key={note.version}>
      <summary>
        <span><strong>{note.version}</strong><span>{note.title}</span><small>{new Date(note.date + 'T12:00:00').toLocaleDateString('uk', { day: 'numeric', month: 'long', year: 'numeric' })}</small></span>
      </summary>
      <article className="release-note-content">
        <p className="release-personal">{note.message}</p>
        <h2>Що я змінила</h2>
        <ul className="release-changes">{note.changes.map(change => <li key={change}>{change}</li>)}</ul>
        {note.checks.length > 0 && <h2>Що тобі перевірити</h2>}
        {note.checks.map(check => <section className="release-check" key={check.title}>
          <h3>{check.title}</h3>
          <ol>{check.steps.map(step => <li key={step}>{step}</li>)}</ol>
          <p className="release-check-expected"><strong>Має бути:</strong> {check.expected}</p>
        </section>)}
        <h2>Стан версії</h2>
        <p>{note.verified}</p>
        {note.unfinished.length > 0 && <><h2>Ще в роботі</h2><ul>{note.unfinished.map(item => <li key={item}>{item}</li>)}</ul></>}
      </article>
    </details>)}
  </main>;
}
