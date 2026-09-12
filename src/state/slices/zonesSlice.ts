import type { AppSlice } from '../store';
import type { Zone } from '../../types';
import { fmtDate, toMinutes } from '../../utils/date';
import { ruleIdx, addDaysDs } from './plannerHelpers';

/** Перенесено 1:1 з www/index.html, рядки 1185–1191 (сідові дані). */
const SEED_ZONES: Zone[] = [
  { id: 1, nm: 'Сон', color: '#4f8ef7', slots: [{ s: '00:00', e: '06:00' }, { s: '22:00', e: '24:00' }], desc: 'Відпочинок', prio: 1 },
  { id: 2, nm: 'Ранок', color: '#f5a623', slots: [{ s: '06:00', e: '09:00' }], desc: 'Ранкова рутина', prio: 1 },
  { id: 3, nm: 'Робота', color: '#7ed321', slots: [{ s: '09:00', e: '13:00' }, { s: '14:00', e: '18:00' }], desc: 'Продуктивний час', prio: 1 },
  { id: 4, nm: 'Обід', color: '#ff6b35', slots: [{ s: '13:00', e: '14:00' }], desc: 'Прийом їжі', prio: 1 },
  { id: 5, nm: 'Вечір', color: '#bd10e0', slots: [{ s: '18:00', e: '22:00' }], desc: 'Відпочинок/сім\'я', prio: 1 },
];

const ZONE_PALETTE = ['#4f8ef7', '#f5a623', '#7ed321', '#ff6b35', '#bd10e0', '#50e3c2', '#e24b4a', '#9b59b6', '#f8e71c', '#16a085'];

/** Обчислене використання зони (було глобальне `_zUse`, рядок 1343) */
export interface ZoneUsage {
  bound: boolean;
  past: boolean;
  future: boolean;
  nextDs: string;
  nextS: string;
  lastDs: string;
  tpls: string[];
}

/** Активна зона, повернута getActiveZones() — може бути «віртуальною» (план дня/вільний час) */
export interface ActiveZone {
  id: number;
  nm: string;
  color: string;
  desc: string;
  prio: number;
  slots: Zone['slots'];
  planDay?: boolean;
}

const FREE_TIME_ZONE: ActiveZone = { nm: 'Вільний час', color: '#888', desc: '', prio: 0, id: 0, slots: [] };

export interface ZonesSlice {
  zones: Zone[];
  lastZoneId: number;
  /** _zUse — рядок 1343 */
  zoneUsage: Record<number, ZoneUsage>;
  /** _zUseDay — рядок 1344, дата останнього перерахунку */
  zoneUsageDay: string;

  /**
   * updateZoneActive() — рядки 1345–1397. Перераховує bound/active/actPin для
   * всіх зон на основі планувальника (planDayZones/dayTemplates/planRules —
   * інший slice, читається через get()). ВИКЛИКАТИ після будь-якої зміни
   * планувальника, що впливає на розмітку зон (застосування графіка,
   * видалення правила, ручна правка дня).
   */
  recalculateZoneUsage: () => void;
  /** getZones(hm) — рядки 1310–1333. Список активних зон зараз, найпріоритетніша перша. */
  getActiveZones: (hm: { h: number; m: number }) => ActiveZone[];
  /** toggleZoneOff(id) — рядки 1407–1417, без прямих DOM-викликів (render — робота компонента) */
  toggleZoneOff: (id: number) => void;
  /** zoneSortKey(z) — рядки 1400–1405 */
  zoneSortKey: (zone: Zone) => string;
  /** zoneSlotRange(z,hm) — рядки 1419–1427 */
  zoneSlotRange: (zone: Zone, hm: { h: number; m: number }) => string;
  /** planNewZone() — рядки 3852–3859, без DOM/select-фокусу (те — робота компонента) */
  addZone: (name: string) => number;
  /** delZone(id) — видалення зони (тіло не інспектовано детально; типова реалізація — filter + перерахунок) */
  deleteZone: (id: number) => void;
}

export const createZonesSlice: AppSlice<ZonesSlice> = (set, get) => ({
  zones: SEED_ZONES,
  lastZoneId: -1,
  zoneUsage: {},
  zoneUsageDay: '',

  recalculateZoneUsage: () => {
    const today = fmtDate(new Date());
    const zoneUsage: Record<number, ZoneUsage> = {};
    const u = (zid: number): ZoneUsage =>
      zoneUsage[zid] || (zoneUsage[zid] = { bound: false, past: false, future: false, nextDs: '', nextS: '', lastDs: '', tpls: [] });

    const { planDayZones, dayTemplates, planRules } = get();

    Object.keys(planDayZones || {}).forEach((ds) => {
      (planDayZones[ds] || []).forEach((b) => {
        if (b.ov) return; // override дня — не прив'язка
        const x = u(b.zoneId);
        x.bound = true;
        if (ds >= today) {
          x.future = true;
          if (!x.nextDs || ds < x.nextDs || (ds === x.nextDs && b.s < x.nextS)) {
            x.nextDs = ds;
            x.nextS = b.s;
          }
        } else {
          x.past = true;
          if (ds > x.lastDs) x.lastDs = ds;
        }
      });
    });

    (dayTemplates || []).forEach((tp) => {
      (tp.blocks || []).forEach((b) => {
        const x = u(b.zoneId);
        x.bound = true;
        if (x.tpls.indexOf(tp.name) < 0) x.tpls.push(tp.name);
      });
    });

    (planRules || []).forEach((r) => {
      const len = r.days.length;
      r.days.forEach((e, idx) => {
        if (!e || !e.tplId) return;
        const tp = (dayTemplates || []).find((t) => t.id === e.tplId);
        if (!tp) return;
        (tp.blocks || []).forEach((b) => {
          const x = u(b.zoneId);
          x.bound = true;
          if (r.start < today) {
            x.past = true;
            const lp = r.end < today ? r.end : addDaysDs(today, -1);
            if (lp > x.lastDs) x.lastDs = lp;
          }
          if (r.end >= today) {
            const from = today > r.start ? today : r.start;
            for (let k = 0; k < len; k++) {
              const ds2 = addDaysDs(from, k);
              if (ds2 > r.end) break;
              if (ruleIdx(r, ds2) === idx) {
                x.future = true;
                if (!x.nextDs || ds2 < x.nextDs || (ds2 === x.nextDs && b.s < x.nextS)) {
                  x.nextDs = ds2;
                  x.nextS = b.s;
                }
                break;
              }
            }
          }
        });
      });
    });

    const zones = get().zones.map((z) => {
      const x = zoneUsage[z.id];
      const bound = !!(x && x.bound);
      if (z.off) return { ...z, bound, active: false };
      if (!bound) return { ...z, bound, active: true };
      if (x.future) {
        const rest = { ...z };
        delete rest.actPin;
        return { ...rest, bound, active: true };
      }
      return { ...z, bound, active: !!z.actPin };
    });

    set({ zoneUsage, zoneUsageDay: today, zones });
  },

  getActiveZones: (hm) => {
    const cur = hm.h * 60 + hm.m;
    const todayStr = fmtDate(new Date());
    const { zones, planDayOff } = get();
    const dayOff = planDayOff[todayStr] || [];
    let found: ActiveZone[] = [];

    zones.forEach((z) => {
      if (z.active === false) return;
      if (z.bound && !z.actPin) return;
      if (dayOff.indexOf(z.id) >= 0) return;
      (z.slots || []).forEach((sl) => {
        const s = toMinutes(sl.s);
        let e = toMinutes(sl.e);
        if (e === 0) e = 1440;
        if (cur >= s && cur < e && !found.find((f) => f.id === z.id)) {
          found.push({ id: z.id, nm: z.nm, color: z.color, desc: z.desc || '', prio: z.prio || 1, slots: z.slots });
        }
      });
    });

    get().dayBlocks(todayStr).forEach((b) => {
      const s = toMinutes(b.s);
      let e = toMinutes(b.e);
      if (e === 0) e = 1440;
      if (cur >= s && cur < e) {
        const z = zones.find((zz) => zz.id === b.zoneId);
        if (z) {
          found = found.filter((f) => f.id !== z.id);
          found.push({ id: z.id, nm: z.nm, color: z.color, desc: (z.desc || '') + ' · план дня', prio: (z.prio || 1) + 10, slots: z.slots, planDay: true });
        }
      }
    });

    found.sort((a, b) => b.prio - a.prio);
    return found.length ? found : [FREE_TIME_ZONE];
  },

  toggleZoneOff: (id) => {
    const before = get().zones.find((zz) => zz.id === id);
    if (!before) return;
    const wasActive = before.active !== false;
    set((s) => ({
      zones: s.zones.map((zz) => {
        if (zz.id !== id) return zz;
        if (wasActive) {
          const rest = { ...zz, off: true };
          delete rest.actPin;
          return rest;
        }
        const x = get().zoneUsage[id];
        const pinNow = !!(zz.bound && !(x && x.future));
        return pinNow ? { ...zz, off: false, active: true, actPin: true } : { ...zz, off: false, active: true };
      }),
      lastZoneId: -1,
    }));
    get().recalculateZoneUsage();
  },

  zoneSortKey: (zone) => {
    const today = fmtDate(new Date());
    const x = get().zoneUsage[zone.id];
    if (zone.bound && !zone.actPin) return x && x.nextDs ? x.nextDs + ' ' + x.nextS : '9999-99-99 99:99';
    const ss = (zone.slots || []).map((s) => s.s).sort();
    return today + ' ' + (ss[0] || '99:99');
  },

  zoneSlotRange: (zone, hm) => {
    const cur = hm.h * 60 + hm.m;
    for (const sl of zone.slots || []) {
      const s = toMinutes(sl.s);
      let e = toMinutes(sl.e);
      if (e === 0) e = 1440;
      if (cur >= s && cur < e) return sl.s + '–' + sl.e;
    }
    return zone.slots && zone.slots[0] ? zone.slots[0].s + '–' + zone.slots[0].e : '';
  },

  addZone: (name) => {
    const id = get().zones.reduce((m, z) => Math.max(m, z.id || 0), 0) + 1;
    const zone: Zone = { id, nm: name, color: ZONE_PALETTE[id % ZONE_PALETTE.length], slots: [], desc: '', prio: 1, active: true };
    set((s) => ({ zones: [...s.zones, zone] }));
    return id;
  },

  deleteZone: (id) => {
    set((s) => ({
      zones: s.zones.filter((z) => z.id !== id),
      tasks: s.tasks.map((t) => (t.zoneId === id ? { ...t, zoneId: null, zoneColor: null, zoneName: null } : t)),
    }));
    get().recalculateZoneUsage();
  },
});
