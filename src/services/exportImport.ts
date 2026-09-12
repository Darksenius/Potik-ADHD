import { useStore } from '../state/store';
import { collectState } from './persistence';
import { flowBridge } from '../bridge/nativeBridge';
import { fmtDate } from '../utils/date';
import { ULBL } from '../state/slices/routineSlice';
import type { AppState, Task, Zone, Folder, QuickNote, RecurItem, RareEvent, DayTemplate, WeekTemplate, PlanRule, PlanItem, PlanDayZoneOverride } from '../types';

/** exportBackup() — рядки 2593-2605. ПОВНА копія (те, що читає імпорт). */
export function exportBackupFile(): void {
  const json = JSON.stringify(collectState());
  const fname = 'flow-backup-' + fmtDate(new Date()) + '.json';
  const fb = flowBridge();
  if (fb) {
    try {
      fb.exportTxt(fname, json);
      return;
    } catch {
      /* fallback нижче */
    }
  }
  downloadBlob(json, fname, 'application/json;charset=utf-8');
}

function downloadBlob(content: string, fname: string, mime: string): void {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fname;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

/** buildReadableMd() — рядки 2609-2660. Читабельний Markdown-звіт (НЕ бекап). */
export function buildReadableMd(): string {
  const s = useStore.getState();
  const today = fmtDate(new Date());
  const L: string[] = [];
  L.push('# Потік — експорт ' + today + ' ' + new Date().toLocaleTimeString('uk', { hour: '2-digit', minute: '2-digit' }));
  L.push('');

  const prios = s.priorities
    .filter((x): x is number => !!x)
    .map((id) => s.tasks.find((t) => t.id === id)?.title)
    .filter((x): x is string => !!x);
  if (prios.length) {
    L.push('## 🎯 Пріоритети');
    prios.forEach((p) => L.push('1. ' + p));
    L.push('');
  }

  const act = s.tasks.filter((t) => !t.done && !t.someday && !t.trashed);
  const don = s.tasks.filter((t) => t.done && !t.trashed);
  const idea = s.tasks.filter((t) => t.someday && !t.trashed);
  L.push('## ✅ Задачі');
  if (act.length) {
    L.push('### Активні (' + act.length + ')');
    act.forEach((t) =>
      L.push(
        '- [ ] ' +
          t.title +
          (t.zoneName ? ' · 🕐 ' + t.zoneName : '') +
          (t.schedDate ? ' · 📅 ' + t.schedDate + ' ' + (t.schedTime || '') : '') +
          (t.alarmTime ? ' · ⏰ ' + t.alarmTime : '')
      )
    );
    L.push('');
  }
  if (don.length) {
    L.push('### Виконані (' + don.length + ')');
    don.forEach((t) => L.push('- [x] ' + t.title + (t.doneDate ? ' · ' + t.doneDate : '')));
    L.push('');
  }
  if (idea.length) {
    L.push('### 💡 Колись / Ідеї (' + idea.length + ')');
    idea.forEach((t) => L.push('- ' + t.title));
    L.push('');
  }

  if (s.recur.length) {
    L.push('## 🔁 Рутина (сьогодні)');
    s.recur.forEach((r) => L.push('- ' + r.nm + ': ' + (r.unit === 'check' ? (r.done ? '✓ виконано' : '○ ні') : r.val + ' ' + (ULBL[r.unit] || ''))));
    L.push('');
  }

  if (s.qnotes.length) {
    L.push('## 📝 Нотатки');
    s.folders.forEach((f) => {
      const ns = s.qnotes.filter((n) => n.folder === f.id);
      if (!ns.length) return;
      L.push('### ' + (f.ico || '') + ' ' + f.nm);
      ns.forEach((n) => L.push('- ' + (n.date ? n.date + ' ' : '') + (n.time ? n.time + ' — ' : '') + n.txt));
      L.push('');
    });
    const orphan = s.qnotes.filter((n) => !s.folders.some((f) => f.id === n.folder));
    if (orphan.length) {
      L.push('### 📦 Інше');
      orphan.forEach((n) => L.push('- ' + n.txt));
      L.push('');
    }
  }

  if (s.notepad && s.notepad.trim()) {
    L.push('## 🗒 Блокнот');
    L.push(s.notepad.trim());
    L.push('');
  }

  const dn = Object.keys(s.planDayLog || {})
    .filter((k) => s.planDayLog[k]?.note && String(s.planDayLog[k].note).trim())
    .sort();
  if (dn.length) {
    L.push('## 📝 Примітки днів');
    dn.forEach((k) => {
      L.push('### ' + k);
      L.push(String(s.planDayLog[k].note).trim());
      L.push('');
    });
  }

  if (s.zones.length) {
    L.push('## 🕐 Зони');
    s.zones.forEach((z) =>
      L.push('- ' + z.nm + (z.active === false ? ' (неактивна)' : '') + ': ' + (z.slots || []).map((sl) => sl.s + '–' + sl.e).join(', ') + (z.desc ? ' — ' + z.desc : ''))
    );
    L.push('');
  }

  if (s.planRules.length) {
    L.push('## 🗓 Застосовані графіки');
    s.planRules.forEach((r) => L.push('- ' + r.name + ': ' + r.start + ' → ' + r.end));
    L.push('');
  }

  return L.join('\n');
}

/** exportReadable() — рядки 2661-2673. */
export function exportReadableFile(): void {
  const md = buildReadableMd();
  const fname = 'flow-export-' + fmtDate(new Date()) + '.md';
  const fb = flowBridge();
  if (fb) {
    try {
      fb.exportTxt(fname, md);
      return;
    } catch {
      /* fallback нижче */
    }
  }
  downloadBlob(md, fname, 'text/markdown;charset=utf-8');
}

/** AI_PROMPT — рядки 2679-2689. */
export const AI_PROMPT =
  'Ти — помічник з продуктивності для людини з РДУГ (ADHD). Нижче — експорт її ' +
  'застосунку «Потік»: задачі, часові зони дня, рутина, нотатки, пріоритети.\n\n' +
  'Проаналізуй це й допоможи, враховуючи особливості РДУГ (складно почати, ' +
  'утримати увагу, відчути час, розставити пріоритети). Зокрема:\n' +
  '1. Що варто робити в першу чергу сьогодні (3–5 пунктів) і чому.\n' +
  '2. Які задачі краще розбити на дрібніші кроки — запропонуй розбивку.\n' +
  '3. Які задачі прив’язати до яких часових зон дня для кращого ритму.\n' +
  '4. Що схоже на дублікати, застаріле або «висить мертвим вантажем» — що прибрати.\n' +
  '5. М’які, конкретні поради без засуджування й без знецінення.\n\n' +
  'Відповідай українською, стисло й по-доброму. Не вигадуй даних, яких немає.';

/** aiAnalyze() — рядки 2700-2709. Повертає текст для копіювання компонентом (без document.execCommand-фолбеку — те малює aiShowFallback у Кроці 3). */
export function buildAiPromptText(): string {
  return AI_PROMPT + '\n\n---\n\n' + buildReadableMd();
}

/**
 * mergeState() — рядки 2734-2845. ПОВНИЙ перенос злиття з перешиванням id
 * (zoneMap/taskMap/tplMap), рядок-в-рядок. Працює напряму з useStore
 * (не є дією одного slice — торкається майже всіх доменів одразу).
 */
export function mergeState(raw: unknown): number {
  if (!raw || typeof raw !== 'object') return 0;
  const d = raw as Partial<AppState>;
  const s = useStore.getState();
  let added = 0;

  const zoneMap: Record<number, number> = {};
  const taskMap: Record<number, number> = {};
  const tplMap: Record<string, string> = {};

  const zones: Zone[] = s.zones.slice();
  if (Array.isArray(d.zones)) {
    d.zones.forEach((z) => {
      if (!z || !z.nm || z.id === undefined) return;
      const ex = zones.find((x) => x.nm === z.nm);
      if (ex) {
        zoneMap[z.id] = ex.id;
        return;
      }
      const nz: Zone = JSON.parse(JSON.stringify(z));
      nz.id = zones.reduce((m, x) => Math.max(m, x.id || 0), 0) + 1;
      zoneMap[z.id] = nz.id;
      zones.push(nz);
      added++;
    });
  }
  const zid = (old: number | null | undefined): number | null | undefined => (old != null && zoneMap[old] !== undefined ? zoneMap[old] : old);

  const folders: Folder[] = s.folders.slice();
  if (Array.isArray(d.folders)) {
    d.folders.forEach((f) => {
      if (!f || !f.id) return;
      if (folders.some((x) => x.id === f.id)) return;
      folders.push(JSON.parse(JSON.stringify(f)));
      added++;
    });
  }

  let nid = s.nid;
  const tasks: Task[] = s.tasks.slice();
  if (Array.isArray(d.tasks)) {
    d.tasks.forEach((t) => {
      if (!t || !t.title) return;
      const ex = tasks.find((x) => x.title === t.title && (x.type || 'simple') === (t.type || 'simple') && !!x.someday === !!t.someday && !x.trashed);
      if (ex) {
        if (t.id !== undefined) taskMap[t.id] = ex.id;
        return;
      }
      const nt: Task = JSON.parse(JSON.stringify(t));
      if (t.id !== undefined) taskMap[t.id] = nid;
      nt.id = nid++;
      nt.trashed = false;
      if (nt.zoneId) nt.zoneId = zid(nt.zoneId) ?? null;
      tasks.push(nt);
      added++;
    });
  }

  const priorities = s.priorities.slice();
  if (Array.isArray(d.priorities)) {
    d.priorities.forEach((pid, i) => {
      if (!pid || i > 2 || priorities[i]) return;
      const mapped = taskMap[pid];
      if (mapped && !priorities.some((x) => x === mapped)) {
        priorities[i] = mapped;
        added++;
      }
    });
  }

  let qnid = s.qnid;
  const qnotes: QuickNote[] = s.qnotes.slice();
  if (Array.isArray(d.qnotes)) {
    d.qnotes.forEach((n) => {
      if (!n || !n.txt) return;
      if (qnotes.some((x) => x.txt === n.txt)) return;
      const nn: QuickNote = JSON.parse(JSON.stringify(n));
      nn.id = qnid++;
      qnotes.push(nn);
      added++;
    });
  }

  const recur: RecurItem[] = s.recur.slice();
  if (Array.isArray(d.recur)) {
    d.recur.forEach((r) => {
      if (!r || !r.nm) return;
      if (recur.some((x) => x.nm === r.nm)) return;
      const nr: RecurItem = JSON.parse(JSON.stringify(r));
      nr.id = 'r' + Date.now() + Math.floor(Math.random() * 1e4);
      recur.push(nr);
      added++;
    });
  }

  const rareEvents: RareEvent[] = s.rareEvents.slice();
  if (Array.isArray(d.rareEvents)) {
    d.rareEvents.forEach((ev) => {
      if (!ev) return;
      if (rareEvents.some((x) => x.nm === ev.nm)) return;
      rareEvents.push(JSON.parse(JSON.stringify(ev)));
      added++;
    });
  }

  const focusChips = s.focusChips.slice();
  if (Array.isArray(d.focusChips)) {
    d.focusChips.forEach((c) => {
      if (c && focusChips.indexOf(c) < 0) {
        focusChips.push(c);
        added++;
      }
    });
  }

  const dayTemplates: DayTemplate[] = s.dayTemplates.slice();
  if (Array.isArray(d.dayTemplates)) {
    d.dayTemplates.forEach((tpl) => {
      if (!tpl || !tpl.name) return;
      const ex = dayTemplates.find((x) => x.name === tpl.name);
      if (ex) {
        if (tpl.id) tplMap[tpl.id] = ex.id;
        return;
      }
      const nt: DayTemplate = JSON.parse(JSON.stringify(tpl));
      nt.id = 'tpl' + Date.now() + Math.floor(Math.random() * 1e4);
      if (tpl.id) tplMap[tpl.id] = nt.id;
      (nt.blocks || []).forEach((b) => {
        b.zoneId = zid(b.zoneId) ?? b.zoneId;
      });
      dayTemplates.push(nt);
      added++;
    });
  }
  const tid = (old: string | undefined): string | undefined => (old !== undefined && tplMap[old] !== undefined ? tplMap[old] : old);

  const weekTemplates: WeekTemplate[] = s.weekTemplates.slice();
  if (Array.isArray(d.weekTemplates)) {
    d.weekTemplates.forEach((tpl) => {
      if (!tpl || !tpl.name || !Array.isArray(tpl.days)) return;
      if (weekTemplates.some((x) => x.name === tpl.name)) return;
      const nt: WeekTemplate = JSON.parse(JSON.stringify(tpl));
      nt.id = 'wk' + Date.now() + Math.floor(Math.random() * 1e4);
      nt.days.forEach((e) => {
        if (e && e.tplId) e.tplId = tid(e.tplId) || e.tplId;
      });
      weekTemplates.push(nt);
      added++;
    });
  }

  const planRules: PlanRule[] = s.planRules.slice();
  if (Array.isArray(d.planRules)) {
    d.planRules.forEach((r) => {
      if (!r || !r.start || !Array.isArray(r.days)) return;
      if (planRules.some((x) => x.start === r.start && x.end === r.end && x.name === r.name)) return;
      const nr: PlanRule = JSON.parse(JSON.stringify(r));
      nr.days.forEach((e) => {
        if (e && e.tplId) e.tplId = tid(e.tplId) || e.tplId;
      });
      planRules.push(nr);
      added++;
    });
  }

  const planItems: Record<string, PlanItem[]> = JSON.parse(JSON.stringify(s.planItems));
  if (d.planItems && typeof d.planItems === 'object') {
    Object.keys(d.planItems).forEach((ds) => {
      const arr = d.planItems![ds];
      if (!Array.isArray(arr)) return;
      if (!planItems[ds]) planItems[ds] = [];
      arr.forEach((it) => {
        if (!it || !it.title) return;
        if (planItems[ds].some((x) => x.title === it.title)) return;
        planItems[ds].push(JSON.parse(JSON.stringify(it)));
        added++;
      });
    });
  }

  const planDayZones: Record<string, PlanDayZoneOverride[]> = JSON.parse(JSON.stringify(s.planDayZones));
  if (d.planDayZones && typeof d.planDayZones === 'object') {
    Object.keys(d.planDayZones).forEach((ds) => {
      const arr = d.planDayZones![ds];
      if (!Array.isArray(arr)) return;
      if (!planDayZones[ds]) planDayZones[ds] = [];
      arr.forEach((b) => {
        if (!b) return;
        const nb: PlanDayZoneOverride = JSON.parse(JSON.stringify(b));
        nb.zoneId = zid(nb.zoneId) ?? nb.zoneId;
        if (planDayZones[ds].some((x) => x.zoneId === nb.zoneId && x.s === nb.s && x.e === nb.e)) return;
        planDayZones[ds].push(nb);
        added++;
      });
    });
  }

  const planDayOff: Record<string, number[]> = JSON.parse(JSON.stringify(s.planDayOff));
  if (d.planDayOff && typeof d.planDayOff === 'object') {
    Object.keys(d.planDayOff).forEach((ds) => {
      const arr = d.planDayOff![ds];
      if (!Array.isArray(arr)) return;
      if (!planDayOff[ds]) planDayOff[ds] = [];
      arr.forEach((z) => {
        const nz = zid(z);
        if (nz != null && planDayOff[ds].indexOf(nz) < 0) {
          planDayOff[ds].push(nz);
          added++;
        }
      });
    });
  }

  useStore.setState({
    zones, folders, tasks, nid, priorities, qnotes, qnid, recur, rareEvents, focusChips,
    dayTemplates, weekTemplates, planRules, planItems, planDayZones, planDayOff,
  });
  useStore.getState().recalculateZoneUsage();

  return added;
}

/** importPickedFile() — рядки 2847-2865, без DOM/alert (те — робота компонента). */
export function importFromFile(file: File): Promise<number> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = (e) => {
      let d: unknown = null;
      try {
        d = JSON.parse(String(e.target?.result));
      } catch {
        reject(new Error('Не вдалося прочитати файл. Потрібен JSON-файл експорту Потік (flow-backup-*.json).'));
        return;
      }
      const added = mergeState(d);
      resolve(added);
    };
    reader.onerror = () => reject(new Error('Помилка читання файлу'));
    reader.readAsText(file);
  });
}
