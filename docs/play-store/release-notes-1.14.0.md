# Примітки до випуску 1.14.0

**Випуск 16 жовтня 2026** (після схвалення production 11300). Окремий патч 1.13.1 не випускали.
Перевірка на телефоні (16.10): шторка, автопродовження, таймер сну, ZIP, CUE/CHAP, великий шрифт — пройшли.

## Що входить у APK

З часів `v1.13.0` у `app/` змінилося (докладно — `ROADMAP.md`, «Після v1.13.0»):

- **Android Auto і сповіщення:** обкладинки в списках і шторці (`CoverProvider`); кнопки «Назад/Вперед N с» і
  «Закладка»; голосовий пошук і пошук на полиці — один механізм (ё/е, апострофи, кілька слів); сторінки списків
  без переповнення.
- **Слухання:** автопродовження після короткого розриву навушників і нічний таймер сну (обидва вимкнені за
  замовчуванням); перемотка на паузі зберігається; мініплеєр не заходить під бічну навігацію в ландшафті.
- **Імпорт:** книга з ZIP; глави з ID3 `CHAP` і CUE; порядок глав за тегами, коли імена його не дають; диски з
  бонусом — одна книга, теки «Глава N» — не книги, `.trashed-*`/`__MACOSX` ігноруються.
- **Верстка:** великий шрифт (заголовок, вкладки, кнопки, налаштування), екран книги на планшеті.
- **Надійність:** відновлення копії без придатних книг не стирає полицю; WebDAV — ліміт відповіді 32 МБ.

Схема Room і формат копії не змінилися (**12** і **v4**).

**Чого не обіцяти:** сітка обкладинок і смужка прослуханого в Auto досі не малюються на DHU 2.0; автопродовження з
навушниками, нічний таймер уночі, книги з CHAP/CUE/ZIP і кнопки в Auto-машині не перевірені на живих даних (див.
`TESTING.md`, «Що не покрито»).

## Поле «Примітки до випуску» в Play Console (≤ 500 символів на мову)

```
<en-US>
Cover art in Android Auto and notifications, bookmark and skip buttons in the notification, resume after headphones reconnect and a night sleep timer (both off by default), importing a book from a ZIP, chapters from ID3 and CUE, smarter search, large-font fixes.
</en-US>
<uk>
Обкладинки в Android Auto і сповіщеннях, кнопки закладки й перемотки в сповіщенні, продовження після повернення навушників і нічний таймер сну (вимкнені за замовчуванням), імпорт книги з ZIP, глави з ID3 і CUE, розумніший пошук, виправлення великого шрифту.
</uk>
```

## Нотатки для GitHub Release `v1.14.0`

```
Android Auto / notification: cover art (read-only content provider), bookmark and skip buttons.
Listening: resume after headphones reconnect and a night sleep timer (both off by default); seeks while paused are saved.
Import: books from ZIP archives; chapters from ID3 CHAP tags and CUE sheets; track-number ordering; disc/bonus folder grouping; junk files ignored.
Search: case, diacritics, ё/е, apostrophes, multi-word. Large-font layout fixes. Backup restore and WebDAV hardening.
No schema or backup format changes: Room 12, backup v4.
```

## Перед тегом

1. `scripts/ci-local.ps1 -Instrumented -Avd <AVD>` — на API 26 (`BV_API26`), 30 (`Medium_Phone`) і 35
   (`BV_API35`) уже зелені на `master` (15 жовтня 2026, 102 інструментальні); перегнати, якщо в `app/` з'явиться ще
   щось. Окремо перевірити на телефоні нове з «Що не покрито» в `TESTING.md`.
2. `gradlew.bat :app:bundleRelease '-PreleaseTag=v1.14.0'` → `versionCode` 11400.
3. Після тега підняти `devVersion` до `1.15.0-dev`.
4. Синхронізувати публічне дзеркало лише після виходу (`scripts/public-mirror/HOWTO.md`).
