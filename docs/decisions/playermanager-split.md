# План: поділ `PlayerManager.kt`

Складено 13 листопада 2026. Виконувати **після** виходу 1.18.0 і 7 днів у production без
нових збоїв — не разом із релізом. Мета — щоб наступна фіча в плеєрі не додавала ще сотню
рядків у файл на 1832 рядків, а потрапляла в окремий, протестований клас.
Діапазони рядків у таблиці нижче — зріз на 13 листопада, не поточні номери.

## Принципи

- **Нуль змін поведінки.** Кожен крок — переміщення коду з делегуванням; публічний API
  `PlayerManager` (його викликають ViewModel, `PlaybackService`, тести) не змінюється.
- **Один крок — один коміт** із повним прогоном (нижче). Не змішувати з фічами.
- Не чіпати найризикованіше: підключення `MediaController` (`connectControllerLocked`,
  `adoptRunningSession`, `restoreLastBookIfNeeded`, `prepareLocked`) і слухач плеєра —
  там гонки холодного старту, виправлені в 1.17.0.

## Карта файла (рядки — станом на `e6666ea`)

| Блок | Рядки | ≈ | Залежить від |
|---|---|---:|---|
| Таймер сну, нічний розклад, струшування, згасання | 866–1025, 1118–1207, 1767–1806 | 400 | `_state`, `liveController()`, `settings`, `scope`, `shakeDetector`; викликається з `publish()` (1613), слухача (332 — чи активний сон перед автопереходом черги), колектора налаштувань (428–433), `release()` (1227–1229) |
| Черга «Далі» | 1027–1116 | 90 | `_state.queue`, `repo`, `queueSaveMutex`, `lastSavedQueueIds`; автоперехід — у слухачі (333–345) |
| Облік прослуховування й збереження позиції | 1588–1607, 1680–1765 | 110 | `_state`, `liveController()`, `repo`, `playbackStarted`, `lastPersisted*`, `pendingListenMs`, `userSeekAtMs` |
| Повтор A–B | `toggleAbLoop`/`clearAbLoop` + перевірка в `publish()` | 40 | логіка вже в `AbLoop.kt`; лишити як є |
| Підключення, prepare, слухач, тікер | решта | ~1100 | — не чіпаємо |

## Крок 0 — страхувальні тести (окремий коміт, до будь-якого переносу)

- **Черга не має жодного тесту.** Винести чисті операції в `playback/QueueOps.kt`
  (`add` без дублікатів, `remove`, `move(from, to)` з межами, `batchAdd` у порядку виділення,
  «що грати наступним після кінця книги») і покрити `QueueOpsTest` — включно з
  крайовими випадками: переміщення останнього, дублікат поточної книги, порожня черга.
- **Таймер сну:** `SleepTimerTest` (8 інструментальних) уже є. Додати юніт-тести на те,
  що буде новим кордоном: «сон активний» блокує автоперехід черги (рядок 332).

## Крок 1 — `SleepTimerController` (~400 рядків)

Новий `playback/SleepTimerController.kt`. Переїжджають: `sleepJob`, `sleepGraceJob`,
`lastSleepMode`, `nightTimerDismissedAtElapsedMs`, `endOfChapterTargetIndex`,
`shakeDetector` і всі функції з першого рядка таблиці.

```kotlin
internal class SleepTimerController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PlayerUiState>,   // той самий _state
    private val controller: () -> MediaController?,       // liveController
    private val settings: () -> UserSettings,
    private val haptics: () -> Unit,                       // triggerHaptic
    shakeDetectorFactory: (onShake: () -> Unit) -> ShakeDetector,
) {
    val isActive: Boolean                    // для автопереходу черги
    fun startMinutes(minutes: Int); fun untilChapterEnd(); fun untilBookEnd(); fun cancel()
    fun onPlaying()                          // з publish(): зняти пільгове вікно
    fun onSettings(s: UserSettings)          // з колектора: струшування
    fun maybeStartNight()                    // нічний розклад
    fun release()
}
```

`PlayerManager.startSleep(...)` і решта лишаються однорядковими делегатами — для UI нічого
не змінюється. `resetNightTimerDismissal()` (тести) теж делегує.

## Крок 2 — `PlaybackQueue` (~90 рядків)

`playback/PlaybackQueue.kt` поверх `QueueOps` з кроку 0: `add/remove/move/clear/batchAdd`,
`restore/reload`, збереження в `repo` під `queueSaveMutex`. Автоперехід у слухачі плеєра
лишається в `PlayerManager`, але питає `queue.next()` і `sleep.isActive`.

## Крок 3 — `ProgressRecorder` (~110 рядків)

`persist`, `flushListening`, `lastPersisted*`, `pendingListenMs`. `playbackStarted` і
`userSeekAtMs` лишаються в `PlayerManager` (їх читають prepare й тікер) і передаються як
лямбди. Інваріанти зберегти дослівно: книга, лише підвантажена при старті, не піднімається
на полиці; перемотка на паузі зберігає місце, але не чіпає `lastPlayedAt` (від нього
рахується розумний відкат).

**Результат:** `PlayerManager.kt` ≈ 1100 рядків, три нові класи по 90–400 рядків із тестами.

## Перевірка після кожного кроку

1. `scripts/ci-local.ps1` повний (юніт, lint, FOSS, R8, AAB).
2. Інструментальні на `BookVoices_A14`: `PlaybackTest`, `SleepTimerTest`, `SeekPersistTest`,
   `SeekStepTest` (`ci-local.ps1 -Instrumented -Avd BookVoices_A14`).
3. Вручну на телефоні (debug-збірка):
   - крок 1: таймер 1 хв зі згасанням; «до кінця глави»; струснути в пільгове вікно;
     нічний розклад на найближчу хвилину; скасування не вмикає таймер знову;
   - крок 2: дві книги в черзі, дослухати першу → друга стартує; при активному таймері
     сну — не стартує; переставити, видалити, перезапуск застосунку зберігає чергу;
   - крок 3: послухати 30 с → `force-stop` → позиція й історія на місці; перемотати на
     паузі → `force-stop` → місце збережене, книга не піднялася вгору полиці.

## Ризики

- `withLiveController { it.volume = 1f }` у `clearSleepInternal` і згасання в
  `fadeAndPause` працюють із живим контролером — у контролері сну брати його лише через
  лямбду, не кешувати (контролер перепідключається після смерті сервісу).
- Порядок у `release()`: таймери скасовувати до звільнення контролера, як зараз.
- Тест-хуки (`resetNightTimerDismissal`) лишити в `PlayerManager`, щоб не правити тести разом
  із переносом.
