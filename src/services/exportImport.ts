import { useStore } from '../state/store';
import { collectState, applyState, saveState, releaseSaveGuard, saveIsBlocked, blockSavingForRecovery, getRecoverySources, readRecoveryArchive } from './persistence';
import { validateBackup, sameBackupValue } from './backupValidation';
import { flowBridge } from '../bridge/nativeBridge';
import { fmtDate } from '../utils/date';
import { ULBL } from '../state/slices/routineSlice';
import { normalizePlannerTasks } from './plannerMigration';
import { normalizeRoutineTasks } from './routineMigration';
import { remapHistoryIdentity, seedKnownCompletions } from '../utils/taskHistory';
import type { Task, Zone, Folder, QuickNote, RareEvent, DayTemplate, WeekTemplate, PlanRule, PlanItem, PlanDayZoneOverride } from '../types';

/** exportBackup() — рядки 2593-2605. ПОВНА копія (те, що читає імпорт). */
export function exportBackupFile(): void {
  const json = JSON.stringify(collectState());
  const fname = 'flow-backup-' + fmtDate(new Date()) + '.json';
  const fb = flowBridge();
  if (fb) {
    try {
      if (fb.exportTxt(fname, json) !== false) return;
    } catch { /* Report a native failure; WebView downloads are not supported. */ }
    useStore.getState().showToast('Android не зміг зберегти файл експорту. Спробуй ще раз.');
    return;
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

export function exportRecoveryFile(archived = false, name?: string): void {
  const raw = archived ? readRecoveryArchive(name) : getRecoverySources();
  if (!raw || raw === '[]') { useStore.getState().showToast('Архіву відновлення ще немає.'); return; }
  const filename = archived ? (name || 'flow-recovery-archive.json') : 'flow-recovery-originals.json';
  const fb = flowBridge();
  if (fb) {
    try {
      if (fb.exportTxt(filename, raw) !== false) return;
    } catch { /* Show an actionable error rather than claiming a download. */ }
    useStore.getState().showToast('Не вдалося відкрити збереження архіву. Спробуй ще раз.');
    return;
  }
  downloadBlob(raw, filename, 'application/json;charset=utf-8');
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
      if (fb.exportTxt(fname, md) !== false) return;
    } catch { /* Report a native failure; WebView downloads are not supported. */ }
    useStore.getState().showToast('Android не зміг зберегти файл експорту. Спробуй ще раз.');
    return;
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
  const parsed = validateBackup(raw);
  const normalized = normalizePlannerTasks(parsed.tasks || [], parsed.nid || 1, parsed.planItems || {});
  const routineNormalized = normalizeRoutineTasks(normalized.tasks, parsed.recur || [], normalized.nid, parsed.saveDate);
  const d = { ...parsed, ...normalized, tasks: routineNormalized.tasks, nid: routineNormalized.nid, recur: [],
    taskHistory: seedKnownCompletions(parsed.taskHistory || [], routineNormalized.tasks) };
  const s = useStore.getState();
  let added = 0;

  const zoneMap: Record<number, number> = {};
  const taskMap: Record<number, number> = {};
  const tplMap: Record<string, string> = {};
  const tid = (old: string | undefined): string | undefined => (old !== undefined && tplMap[old] !== undefined ? tplMap[old] : old);
  const retainedZoneIds = new Set<number>();
  const retainZoneId = (value: unknown) => {
    if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) retainedZoneIds.add(value);
  };
  s.zones.forEach(zone => retainZoneId(zone.id));
  s.tasks.forEach(task => {
    retainZoneId(task.zoneId);
    retainZoneId(task.questDeferral?.sourceZoneId);
    retainZoneId(task.questDeferral?.targetZoneId);
    retainZoneId(task.completionDeferral?.sourceZoneId);
    retainZoneId(task.completionDeferral?.targetZoneId);
  });
  s.taskHistory.forEach(event => retainZoneId(event.zoneId));
  d.taskHistory.forEach(event => retainZoneId(event.zoneId));
  Object.values(s.planDayZones || {}).forEach(blocks => blocks.forEach(block => retainZoneId(block.zoneId)));
  Object.values(s.planDayOff || {}).forEach(ids => ids.forEach(retainZoneId));
  s.dayTemplates.forEach(template => {
    template.blocks.forEach(block => retainZoneId(block.zoneId));
    (template.tasks || []).forEach(entry => retainZoneId(entry.task.zoneId));
  });
  retainZoneId(s.lastZoneId);
  d.zones?.forEach(zone => retainZoneId(zone.id));
  d.tasks?.forEach(task => {
    retainZoneId(task.zoneId);
    retainZoneId(task.questDeferral?.sourceZoneId);
    retainZoneId(task.questDeferral?.targetZoneId);
    retainZoneId(task.completionDeferral?.sourceZoneId);
    retainZoneId(task.completionDeferral?.targetZoneId);
  });
  Object.values(d.planDayZones || {}).forEach(blocks => blocks.forEach(block => retainZoneId(block.zoneId)));
  Object.values(d.planDayOff || {}).forEach(ids => ids.forEach(retainZoneId));
  d.dayTemplates?.forEach(template => {
    template.blocks.forEach(block => retainZoneId(block.zoneId));
    (template.tasks || []).forEach(entry => retainZoneId(entry.task.zoneId));
  });
  const allocateZoneId = () => {
    let id = 1;
    while (retainedZoneIds.has(id)) id++;
    retainedZoneIds.add(id);
    return id;
  };

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
      nz.id = allocateZoneId();
      zoneMap[z.id] = nz.id;
      zones.push(nz);
      added++;
    });
  }
  const zid = (old: number | null | undefined): number | null | undefined => (old != null && zoneMap[old] !== undefined ? zoneMap[old] : old);
  const templateIds = new Set(s.dayTemplates.map((template) => template.id));
  const templateIdsByName = new Map<string, string>();
  s.dayTemplates.forEach((template) => {
    if (!templateIdsByName.has(template.name)) templateIdsByName.set(template.name, template.id);
  });
  const allocateTemplateId = () => {
    const base = `tpl${Date.now()}${Math.floor(Math.random() * 1e4)}`;
    let candidate = base;
    let suffix = 1;
    while (templateIds.has(candidate)) candidate = `${base}-${suffix++}`;
    templateIds.add(candidate);
    return candidate;
  };
  (d.dayTemplates || []).forEach((template) => {
    if (!template?.id || !template.name) return;
    const existingId = templateIdsByName.get(template.name);
    const targetId = existingId || allocateTemplateId();
    if (!existingId) templateIdsByName.set(template.name, targetId);
    tplMap[template.id] = targetId;
  });
  const remapDayPresetKey = (value: string | undefined): string | undefined => {
    if (!value) return value;
    const separator = value.indexOf(':');
    if (separator < 0) return value;
    const sourceTemplateId = value.slice(0, separator);
    return `${tid(sourceTemplateId) || sourceTemplateId}${value.slice(separator)}`;
  };
  const remapDeferral = (value: Task['questDeferral']) => value ? {
    ...value,
    sourceZoneId: zid(value.sourceZoneId) ?? value.sourceZoneId,
    targetZoneId: zid(value.targetZoneId) ?? value.targetZoneId,
  } : undefined;
  const remapOccurrenceZones = (value: string | undefined) => value?.replace(
    /(^|\|zone:)(\d{4}-\d{2}-\d{2}:)(\d+)(:)/,
    (_match, prefix: string, day: string, zone: string, separator: string) =>
      prefix + day + (zid(Number(zone)) ?? zone) + separator);
  const remapTaskZones = (task: Task): Task => ({
    ...task,
    zoneId: task.zoneId == null ? task.zoneId : (zid(task.zoneId) ?? task.zoneId),
    dayPresetKey: remapDayPresetKey(task.dayPresetKey),
    questDeferral: remapDeferral(task.questDeferral),
    completionDeferral: remapDeferral(task.completionDeferral),
    zoneOccurrenceKey: remapOccurrenceZones(task.zoneOccurrenceKey),
    occurrenceId: remapOccurrenceZones(task.occurrenceId),
  });

  const folders: Folder[] = s.folders.slice();
  if (Array.isArray(d.folders)) {
    d.folders.forEach((f) => {
      if (!f || !f.id) return;
      if (folders.some((x) => x.id === f.id)) return;
      folders.push(JSON.parse(JSON.stringify(f)));
      added++;
    });
  }

  let nid = Math.max(s.nid, 1, ...s.tasks.map(task => task.id + 1));
  const tasks: Task[] = s.tasks.slice();
  const comparableTask = (task: Task) => {
    const { id: _id, occurrenceId, ...body } = task;
    return { ...body, ...(occurrenceId ? {
      occurrenceId: remapHistoryIdentity(occurrenceId, task.id, task.created, 0),
    } : {}) };
  };
  if (Array.isArray(d.tasks)) {
    d.tasks.forEach((t) => {
      if (!t || !t.title) return;
      const remapped = remapTaskZones(t);
      const ex = tasks.find(x => (remapped.legacyRoutineId && x.legacyRoutineId === remapped.legacyRoutineId)
        || sameBackupValue(comparableTask(x), comparableTask(remapped)));
      if (ex) {
        if (t.id !== undefined) taskMap[t.id] = ex.id;
        return;
      }
      const nt: Task = JSON.parse(JSON.stringify(remapped));
      if (t.id !== undefined) taskMap[t.id] = nid;
      nt.id = nid++;
      if (nt.occurrenceId) nt.occurrenceId = remapHistoryIdentity(nt.occurrenceId, t.id, t.created, nt.id);
      // Preserve trash and completion status from the validated file.
      tasks.push(nt);
      added++;
    });
  }

  const taskHistory = seedKnownCompletions(s.taskHistory, s.tasks);
  for (const event of d.taskHistory) {
    const taskId = taskMap[event.taskId] ?? event.taskId;
    const remapId = (value: string) => remapOccurrenceZones(
      remapHistoryIdentity(value, event.taskId, event.taskCreated, taskId))!;
    const mapped = { ...event, taskId, id: remapId(event.id), occurrenceId: remapId(event.occurrenceId),
      zoneId: event.zoneId == null ? event.zoneId : zid(event.zoneId) };
    const existing = taskHistory.find(item => item.id === mapped.id);
    if (existing && !sameBackupValue(existing, mapped)) throw new Error('Історія містить конфлікт однакового запису. Поточні дані не змінено.');
    if (!existing) { taskHistory.push(mapped); added++; }
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
      if (qnotes.some(x => x.txt === n.txt && x.date === n.date && x.time === n.time && x.folder === n.folder)) return;
      const nn: QuickNote = JSON.parse(JSON.stringify(n));
      nn.id = qnid++;
      qnotes.push(nn);
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

  const dayTemplates: DayTemplate[] = s.dayTemplates.map((template) => JSON.parse(JSON.stringify(template)));
  if (Array.isArray(d.dayTemplates)) {
    d.dayTemplates.forEach((tpl) => {
      if (!tpl || !tpl.name) return;
      const ex = dayTemplates.find((x) => x.name === tpl.name);
      if (ex) {
        const knownEntryIds = new Set((ex.tasks || []).map((entry) => entry.id));
        const additions = (tpl.tasks || []).filter((entry) => entry?.id && entry.task?.title && !knownEntryIds.has(entry.id)).map((entry) => ({
          ...JSON.parse(JSON.stringify(entry)),
          task: { ...entry.task, zoneId: entry.task.zoneId == null ? entry.task.zoneId : (zid(entry.task.zoneId) ?? entry.task.zoneId) },
        }));
        if (additions.length) {
          ex.tasks = [...(ex.tasks || []), ...additions];
          added++;
        }
        return;
      }
      const nt: DayTemplate = JSON.parse(JSON.stringify(tpl));
      nt.id = tid(tpl.id) || allocateTemplateId();
      (nt.blocks || []).forEach((b) => {
        b.zoneId = zid(b.zoneId) ?? b.zoneId;
      });
      (nt.tasks || []).forEach((entry) => {
        entry.task.zoneId = entry.task.zoneId == null ? entry.task.zoneId : (zid(entry.task.zoneId) ?? entry.task.zoneId);
      });
      dayTemplates.push(nt);
      added++;
    });
  }

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
    zones, folders, tasks, taskHistory, nid, priorities, qnotes, qnid, recur: [], rareEvents, focusChips,
    dayTemplates, weekTemplates, planRules, planItems, planDayZones, planDayOff,
  });
  useStore.getState().recalculateZoneUsage();

  return added;
}

export const IMPORT_ROLLBACK_KEY = 'flow_v2_before_import';
export function importState(raw: unknown, mode: 'merge' | 'restore'): number {
  const parsed = validateBackup(raw);
  const before = JSON.stringify(collectState());
  const beforeBlocked = saveIsBlocked();
  const beforeBanner = useStore.getState().backupBanner;
  try { localStorage.setItem(IMPORT_ROLLBACK_KEY, before); }
  catch { throw new Error('Немає місця для копії перед імпортом. Звільни місце й повтори. Наявні дані не змінено.'); }
  let count: number;
  if (mode === 'restore') {
    if (!applyState(parsed, true)) throw new Error('Некоректна резервна копія.');
    count = useStore.getState().tasks.length;
  } else count = mergeState(parsed);
  releaseSaveGuard();
  useStore.getState().dismissBackupBanner();
  useStore.getState().recalculateZoneUsage();
  if (!saveState()) {
    applyState(JSON.parse(before), true);
    if (beforeBlocked) blockSavingForRecovery();
    useStore.getState().setBackupBanner(beforeBanner);
    throw new Error('Не вдалося зберегти імпорт. Попередній стан повернуто.');
  }
  return count;
}
export function rollbackLastImport(): boolean {
  const raw = localStorage.getItem(IMPORT_ROLLBACK_KEY);
  if (!raw) return false;
  const current = JSON.stringify(collectState());
  if (!applyState(JSON.parse(raw), true)) return false;
  if (!saveState()) { applyState(JSON.parse(current), true); return false; }
  localStorage.setItem(IMPORT_ROLLBACK_KEY, current);
  return true;
}
export function importFromFile(file: File, mode: 'merge' | 'restore' = 'merge'): Promise<number> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = e => {
      try { resolve(importState(JSON.parse(String(e.target?.result)), mode)); }
      catch (error) { reject(error instanceof Error ? error : new Error('Не вдалося прочитати резервну копію.')); }
    };
    reader.onerror = () => reject(new Error('Помилка читання файлу'));
    reader.readAsText(file);
  });
}
