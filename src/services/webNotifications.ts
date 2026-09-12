import { buildForegroundNotifPayload } from '../bridge/notifPayload';

let notifGranted = false;

/** requestAndShowNotification() — рядки 4194-4214. Викликати з кнопки в UI (напр. Довідка). */
export function requestAndShowNotification(): void {
  if (typeof Notification === 'undefined') {
    window.alert('Сповіщення не підтримуються в цьому браузері');
    return;
  }
  if (Notification.permission === 'granted') {
    notifGranted = true;
    showPersistentNotification();
    return;
  }
  Notification.requestPermission()
    .then((perm) => {
      if (perm === 'granted') {
        notifGranted = true;
        showPersistentNotification();
      } else {
        window.alert('Дозвіл на сповіщення не надано. Перейди в Налаштування → Застосунки → Потік → Сповіщення і увімкни їх.');
      }
    })
    .catch((e) => console.log('notif permission:', e));
}

/** showPersistentNotification() — рядки 4216-4231. #notif-status/#notif-badge DOM-оновлення — робота компонента (не тут). */
export function showPersistentNotification(): void {
  if (!notifGranted && Notification.permission !== 'granted') return;
  try {
    const txt = buildForegroundNotifPayload();
    new Notification(txt.title, { body: txt.body, tag: 'flow-main', icon: '', silent: true });
  } catch (e) {
    console.log('showNotif error:', e);
  }
}

/** Викликається з хвилинного тіку в main.tsx — рядки 4235-4240. */
export function refreshWebNotificationIfGranted(): void {
  if (typeof Notification === 'undefined') return;
  if (notifGranted || Notification.permission === 'granted') {
    notifGranted = true;
    showPersistentNotification();
  }
}

/** IIFE-перевірка при старті — рядки 4244-4252. Викликати один раз з main.tsx. */
export function checkNotificationPermissionOnBoot(): void {
  if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
    notifGranted = true;
    setTimeout(showPersistentNotification, 2000);
  }
}

export function isNotificationGranted(): boolean {
  return notifGranted || (typeof Notification !== 'undefined' && Notification.permission === 'granted');
}
