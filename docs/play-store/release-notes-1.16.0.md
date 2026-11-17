# Примітки до випуску 1.16.0

**Пакет підготовлено 8 листопада 2026.** Тег і GitHub Release `v1.16.0` — 7 листопада 2026 (23:22 UTC); AAB —
`rel/release-1.16.0/`. **У Play надіслано на перевірку 8 листопада 2026** (production, повний випуск, примітки 7 мовами) — без внутрішнього тестування, за рішенням власника.
Останній опублікований у Play — 11400 (1.14.0), схвалений 4 листопада 2026.

Це один випуск замість двох: 1.15.0 так і не вийшов (тег `v1.15.0` лишився локальним), тож **1.16.0 містить усе
з 1.15.0 плюс нове з 21 жовтня**. Тег `v1.15.0` не пушити — інакше в GitHub зʼявиться реліз, якого немає в Play.

versionName `1.16.0`, versionCode **11600**. Схема Room (**12**) і формат копії (**v4**) не змінилися.

## Що змінилося з 1.14.0

Зі списку 1.15.0 (див. [`release-notes-1.15.0.md`](release-notes-1.15.0.md)): розумний відкат на всіх шляхах
відновлення, швидкість при повторному підключенні, одне правило «остання книга», виправлене відновлення Pro після
перевстановлення, неекспортований приймач віджета, імпорт не переривається, копія бази перед скиданням при
даунґрейді, стан діалогів при повороті екрана, WebDAV закриває зʼєднання.

Нове після 1.15.0:

- **Синхронізація між пристроями:** та сама книга, імпортована на двох пристроях окремо (різні id й адреси
  файлів), впізнається за назвою й відбитком глав. Прогрес, закладки, мітки, персонажі й сесії зливаються в
  наявну книгу замість дубліката. Чужі надгробки закладок, міток і персонажів діють і на такі книги.
- **WebDAV:** відкладена синхронізація через хвилину після зупинки відтворення; короткі паузи склеюються, відновлення
  гри скасовує запит; вимкнення синхронізації скасовує і її.
- **Файли:** осиротілі обкладинки й копії аудіо прибираються при відновленні копії й видаленні; файл, на який
  посилається інша книга, лишається.
- **Книга:** конспект у Markdown (нотатки, персонажі, закладки за порядком глав), власна обкладинка, обкладинка з
  ZIP-архіву, відкриття `.zip` із файлових менеджерів, керування чергою «далі».
- **Таймер і Auto:** нічний таймер памʼятає ручне скасування; у підзаголовку Android Auto — відсоток прогресу.

## Поле «Примітки до випуску» в Play Console (≤ 500 символів на мову)

Перевірено: en-US — 456, uk — 413 символів.

```
<en-US>
Sync between devices is smarter: the same book imported on two devices now merges progress, bookmarks and tags instead of duplicating. WebDAV syncs a minute after you pause. Export a book summary to Markdown, set your own cover or take it from a ZIP, open .zip files from any file manager, manage the Up Next queue. Smart rewind works from every control; fixed Pro restore after reinstall; orphaned files are cleaned up; imports survive leaving the screen.
</en-US>
<uk>
Розумніша синхронізація між пристроями: та сама книга, імпортована на двох пристроях, зливає прогрес, закладки й мітки замість дубліката. WebDAV синхронізується за хвилину після паузи. Конспект книги в Markdown, власна обкладинка чи з ZIP, відкриття .zip із файлових менеджерів, керування чергою. Розумний відкат зі всіх керувань; виправлено відновлення Pro; осиротілі файли прибираються; імпорт не переривається.
</uk>
```

## Нотатки для GitHub Release `v1.16.0`

```
Sync: the same book imported separately on two devices is matched by title and chapter fingerprint and merged (progress, bookmarks, tags, characters, sessions) instead of duplicated; remote bookmark/tag/character tombstones apply to merged books.
WebDAV: deferred sync one minute after playback stops (short pauses coalesce, resuming cancels it).
Files: orphaned covers and audio copies are cleaned up on restore and delete; files still used by another book are kept.
Book: Markdown summary export, custom cover, cover from ZIP, open .zip from file managers, queue management.
Playback: smart rewind on every resume path; speed kept on controller reconnect; one last-book rule; night timer remembers manual cancel; progress percent in Android Auto subtitle.
Pro: fix for restoring a purchase after reinstall.
Security: widget buttons handled by a non-exported receiver.
Import: continues when leaving the screen; no parallel imports.
No schema or backup format changes: Room 12, backup v4.
```

## Стан перевірки перед випуском (8 листопада 2026)

| Перевірка | Результат |
|---|---|
| Юніт-тести `:app:testDebugUnitTest` | зелені, 0 падінь |
| Lint | зелений |
| Release-збірка R8 і AAB | зелені |
| Інструментальні тести (AVD Medium_Phone, API 30) | зелені, 7 хв 10 с |
| AAB `-PreleaseTag=v1.16.0` | versionCode 11600, versionName 1.16.0, підпис `jarsigner -verify` дійсний |
| Перевірка на живому пристрої | **не пройдена** для змін після 1.14.0 (див. нижче) |

AAB і `mapping.txt` лежать локально в `rel/release-1.16.0/` (поза git): `bookvoices-1.16.0-11600.aab`
(SHA-256 `6b1ce40bb65a425cddbc9c06930c0bcbb31b34dbc153a73bcb717f781f63e034`) і `mapping-1.16.0-11600.txt`
(SHA-256 `50840dd2118410fbe5a24a7ca27d7ccdb7be5cfd87d3810d4c31d1ec95f9d741`).
Файл `mapping.txt` завантажити в Play Console разом з AAB: тоді звіти консолі показуватимуть читабельні імена класів.

## Що треба зробити вручну (порядок)

1. **Перевірка на телефоні** (кілька хвилин, чекліст у `TESTING.md`): розумний відкат зі шторки й гарнітури; імпорт
   ZIP і відкриття `.zip` з файлового менеджера; власна обкладинка; конспект у Markdown; черга «далі»; відновлення
   Pro (якщо купував); WebDAV між двома пристроями, якщо є. Не перевірено й лишається поза чеклістом: Android Auto
   у машині.
2. Створити тег і запушити (лише за твоєю командою): `git tag v1.16.0`, `git push origin master v1.16.0`.
   Воркфлоу `release.yml` збере APK і створить GitHub Release.
3. У Play Console → «Створити новий випуск» → завантажити `bookvoices-1.16.0-11600.aab` і `mapping-1.16.0-11600.txt`
   → вставити примітки з блоку вище → «Надіслати на перевірку».
4. Після тега підняти `devVersion` у `build.gradle.kts` до `1.17.0-dev` (інакше крок «devVersion випереджає тег»
   у `ci-local.ps1` упаде).
5. Після схвалення оновити тексти сторінки магазину (чернетка — `listing.md`, розділ «Під 1.16.0») і знімок консолі.

**Чого не обіцяти в тексті:** Android Auto у машині не перевірено; сітка обкладинок і смужка прослуханого в Auto не
малюються на DHU (див. `TESTING.md`).
