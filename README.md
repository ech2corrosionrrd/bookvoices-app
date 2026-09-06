# BookVoices

Офлайн-плеєр для **своїх** аудіокниг на Android. Файли лишаються на диску,
акаунт не потрібен, реклами немає. Мережа — лише для опційного WebDAV-бекапу
й покупки Pro в Google Play.

| | |
|---|---|
| Модуль | `app/` (`app.bookvoices`) |
| Мін. Android | 8.0 (API 26) |
| Останній реліз | **v1.12.0** |
| Модель | Freemium + Lifetime Pro ($4.99) |

Це публічне дзеркало **лише локального** застосунку. Розробка ведеться в
приватному репозиторії; сюди потрапляють зрізи, готові до відкритого коду.
Підписані APK також можуть зʼявлятися в
[Releases приватного bookvoices](https://github.com/ech2corrosionrrd/bookvoices/releases)
(доступ лише для власників), доки релізи не дублюють сюди.

---

## Можливості (коротко)

- Імпорт файлів і папок (SAF), вкладені диски, обкладинки з теки
- Полиця: пошук, мітки, серії, пін, список/сітка
- Фон, віджет, таймер сну, черга «далі», закладки й нотатки
- Android Auto (дерево, resume, голосовий пошук)
- 7 мов інтерфейсу, теми Ніч / Папір / OLED
- Pro: Skip Silence, Volume Boost, EQ + pitch, автозакладка BT, персонажі

Деталі для користувача й збірки: [`docs/`](docs/), [`BUILD.md`](BUILD.md),
[`ROADMAP.md`](ROADMAP.md).

---

## Поставити

1. [Releases](../../releases) → `bookvoices-<версія>.apk`, **або** Google Play
   (коли опубліковано).
2. Дозвольте встановлення з цього джерела (для APK).
3. Оновлення поверх працює, якщо підпис той самий.

### Додати книги

«+» на полиці → файли / папка / демо. Файли **не копіюються** в застосунок
(крім «Поділитися» з іншого додатка).

### Бекап прогресу

Налаштування → JSON або WebDAV (`https://` лише). Аудіофайли в бекап не їдуть.

---

## Збірка

JDK 17, `ANDROID_HOME`. Деталі — [`BUILD.md`](BUILD.md).

```bat
gradlew.bat :app:assembleDebug
gradlew.bat :app:assembleRelease -PreleaseTag=v1.12.0
gradlew.bat :app:bundleRelease -PreleaseTag=v1.12.0
gradlew.bat testDebugUnitTest lintDebug
```

APK: `app/build/outputs/apk/release/app-release.apk`  
AAB: `app/build/outputs/bundle/release/app-release.aab`

Підпис: скопіюйте `keystore.properties.example` → `keystore.properties`
(файл у `.gitignore`).

---

## Ліцензія

[MIT](LICENSE). Шрифти Cormorant / IBM Plex — за своїми ліцензіями в
`app/src/main/res/font/`.

Питання й баги: [Issues](../../issues).
