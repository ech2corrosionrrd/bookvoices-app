# Примітки до випуску 1.15.0

**Чернетка (випуск не опубліковано; versionCode 11500, готово до випуску).** Зміни після тега 1.14.0: виправлення за оглядом коду локального :app
(коміти 6088896…703e016). Нічого з цього не перевірено на пристрої (не перевірено), лише 392 юніт-тести й ci-local.ps1. Перед завантаженням у Play пройти чекліст із `TESTING.md`.

## Що входить в APK

- **Розумний відкат:** після паузи працює однаково зі шторки, гарнітури, Android Auto, кнопки в застосунку й автовідновлення.
- **Швидкість:** при повторному підключенні контролера береться швидкість активної сесії, а не 1.0.
- **Остання книга:** один алгоритм вибору для автовідновлення й запуску з системи.
- **Pro:** виправлено відновлення покупки після перевстановлення (ITEM_ALREADY_OWNED); колбек відновлення викликається один раз; невдале підтвердження покупки повторюється.
- **Віджет:** кнопки обробляє неекспортований приймач, інші застосунки ними керувати не можуть.
- **Імпорт:** не переривається, якщо вийти з екрана; другий імпорт паралельно не запускається; книга й глава пишуться в одній транзакції.
- **Дані:** перед скиданням бази при даунґрейді створюється копія nichnyk.db.downgrade.bak; біля ліміту дозволів SAF файли копіюються в памʼять застосунку.
- **Інтерфейс:** стан діалогів і текстових полів зберігається при повороті екрана.
- **WebDAV:** зʼєднання завжди закривається, пробіли по краях пароля зберігаються.

Схема Room (**12**) і формат копії (**v4**) не змінилися.

## Поле «Примітки до випуску» в Play Console (≤ 500 символів на мову)

```
<en-US>
Smart rewind after a pause now works from the notification, headset, Android Auto and the in-app button. Playback speed is kept when a controller reconnects, and the last-book choice is consistent. Fixed restoring a Pro purchase after reinstall. The widget buttons can no longer be triggered by other apps. Imports continue when you leave the screen. A backup of the database is made before a downgrade reset; storage-permission limit fallback.
</en-US>
<uk>
Розумний відкат після паузи працює зі шторки, гарнітури, Android Auto і з кнопки в застосунку. Швидкість зберігається при повторному підключенні, вибір останньої книги однаковий скрізь. Виправлено відновлення покупки Pro після перевстановлення. Кнопки віджета не доступні іншим застосункам. Імпорт не переривається, якщо вийти з екрана. Копія бази перед скиданням при даунґрейді; запасний варіант при ліміті дозволів на файли.
</uk>
```

## Нотатки для GitHub Release `v1.15.0`

```
Playback: smart rewind on every resume path (notification, headset, Android Auto, in-app); speed kept on controller reconnect; one last-book rule for auto-resume and system launch.
Pro: fix for restoring a purchase after reinstall (ITEM_ALREADY_OWNED); single restore callback; acknowledge retries.
Security: widget buttons handled by a non-exported receiver.
Import: continues when leaving the screen; no parallel imports; book and chapter saved in one transaction; storage-permission limit fallback copies files into app storage.
UI: dialog and text-field state survives screen rotation.
Data: database backup (nichnyk.db.downgrade.bak) before a downgrade reset; WebDAV connection always closed, passwords keep edge spaces.
No schema or backup format changes: Room 12, backup v4.
```
