# Імпорт прогресу зі Smart AudioBook Player — розвідка (20 листопада 2026)

План 1.19, №1 (див. ROADMAP «План 1.19»). Мета — перенести позиції, швидкість і закладки
людини, яка переходить зі Smart AudioBook Player (SABP, `ak.alizandro.smartaudiobookplayer`).

## Що відомо

Джерела: SABP 11.9.8 на телефоні власника (лише читання: `pm`, `dumpsys`, файли в
`/sdcard`), рядки й байткод із його APK (`aapt2 dump strings`, `dexdump`). Публічного опису
формату немає ні в розробника, ні в спільноти.

- **Експорту прогресу в інтерфейсі немає.** У рядках застосунку з резервного копіювання є лише
  «Backup to USB drive» (копія самих книг на зовнішній носій) та «Import characters from
  Finished book».
- **Системна резервна копія закрита:** у пакета немає `ALLOW_BACKUP`, `adb backup` нічого не дасть.
- **Тека `!Smart AudioBook Player Statistics/`** у корені бібліотеки містить `statistics.xml`
  (`<book><path>Тека книги</path><time>2026-10 3</time></book>`, хвилини за місяць) і обкладинки.
  Позицій у ній немає — для імпорту історії слухання хіба що.
- **Дані книги — три файли:** `position.sabp.dat`, `bookmarks.sabp.xml`, `characters.sabp.xml`.
  - `position.sabp.dat` — **Java-серіалізація** (`ObjectOutputStream`) класу
    `ak.alizandro.smartaudiobookplayer.BookDataBackup`. Поля: `mFileName` (поточний файл),
    `mFilePosition` (позиція у файлі, мс, `int`), `mPlaybackSpeed`, `mBookState` (enum стану —
    нова/розпочата/прочитана), `mLastPlaybackTime`, `mBookCreationTime` (`Date`),
    `mBookQueuePosition`, `mBoostVolume`, `mCoverName`, `mCurrentPlaybackTimeCache`,
    `mEqualizerLevels`, `mLastOpenedTextFile`, а також `serialVersionUID`.
  - Пише їх задача `z8` у **кеш у приватному сховищі SABP** (шлях `mCachePath` — id документа
    SAF з «:» → «/»), а **читає** `BookData.<init>` спершу **з теки книги** (`u70.i(folderUri,
    "position.sabp.dat")`). Тобто якщо покласти файл у теку книги, SABP підхопить прогрес — так,
    схоже, працює відновлення з «Backup to USB drive».
- На телефоні власника `find /sdcard -name "*.sabp.*"` — **0 файлів**: SABP тримає їх лише в
  приватній пам'яті.

## Що з цього випливає

Прочитати прогрес SABP без його участі неможливо. Реальні шляхи:

1. **«Backup to USB drive» → тека → імпорт у BookVoices.** Якщо копія містить
   `position.sabp.dat` у теках книг, BookVoices може читати його при імпорті теки. Не
   перевірено: копіює всі книги (гігабайти) і, можливо, лише в повній версії SABP.
   **Перевірити на емуляторі** (SABP з APK + дві згенеровані книги), а не на телефоні власника.
2. Розбір Java-серіалізації в Kotlin — без `ObjectInputStream` з чужим класом: власний
   мінімальний розбирач потоку (`TC_OBJECT`, `TC_CLASSDESC`, примітиви, `String`, `Date`,
   `Enum`) лише для потрібних полів. Чужий клас не завантажуємо — це й безпечніше.
3. Якщо шлях 1 не дає файлів — №1 закриваємо: лишається ручний перенос (історія з
   `statistics.xml` як утіха, без позицій).

Юридично: розбір формату заради сумісності (перенесення даних самого користувача) — звичайна
практика; код SABP не копіюємо, лише читаємо його файли даних.

## Перевірка на емуляторі (20 листопада 2026) — не вийшло

APK SABP 11.9.8 (base + split arm64/uk/xxhdpi з телефона власника) поставлено на окремий AVD
`BV_SABP` (Android 14, Google APIs x86_64 — з перекладачем arm64; на AOSP-образах
`INSTALL_FAILED_NO_MATCHING_ABIS`). Застосунок не стартує: «Something went wrong. Check that
Google Play is enabled on your device…» — йому потрібні Google Play і, схоже, встановлення з
Play під обліковим записом. Входити в обліковий запис Google на емуляторі — дія власника, тож
перевірка «Backup to USB drive» лишається тільки на його телефоні (копіює всі книги; можливо,
лише повна версія).
