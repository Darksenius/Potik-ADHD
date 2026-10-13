import type { CSSProperties } from 'react';
import '../../styles/icons.css';

const ICONS = ['quest', 'calendar', 'inbox', 'more', 'settings', 'help',
  'note', 'focus', 'zones', 'idea', 'chart', 'folder',
  'check', 'clock', 'repeat', 'counter', 'archive', 'trash',
  'edit', 'plus', 'close', 'expand', 'moon', 'sun'] as const;

export type IconName = typeof ICONS[number];

/** Each icon keeps its labelled control's accessible name; the atlas is decorative. */
export default function Icon({ name, className = '', size }: { name: IconName; className?: string; size?: number }) {
  const index = ICONS.indexOf(name);
  const style: CSSProperties = {
    backgroundPosition: `${(index % 6) * 20}% ${Math.floor(index / 6) * 100 / 3}%`,
    ...(size ? { width: size, height: size } : {}),
  };
  return <span className={'potik-icon ' + className} style={style} aria-hidden="true" />;
}
