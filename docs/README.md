# Документація BookVoices

Публічне дзеркало локального `:app`. Поточні версії та стан релізів — у шапці [`ROADMAP.md`](../ROADMAP.md); у `main`
може лежати робота після нього, бо дзеркало синхронізують і тоді, коли змінюється
щось відкрите, а не лише на реліз. Що саме накопичилося — у [`CHANGELOG.md`](../CHANGELOG.md) і [`ROADMAP.md`](../ROADMAP.md).

Розробка ведеться в приватному репозиторії; сюди потрапляють відкриті зрізи.

| Документ | Для кого |
|---|---|
| [`README.md`](../README.md) | Користувачі: встановлення, імпорт, бекап |
| [`BUILD.md`](../BUILD.md) | Збірка, підпис, AAB, GitHub Release |
| [`TESTING.md`](../TESTING.md) | Тести й CI |
| [`ROADMAP.md`](../ROADMAP.md) | План розвитку |
| [`CHANGELOG.md`](../CHANGELOG.md) | Що вийшло в кожному випуску |
| [`MONETIZATION.md`](MONETIZATION.md) | Freemium + Lifetime Pro |
| [`play-store/`](play-store/) | Google Play: чекліст, listing, privacy, платежі, тестувальники |

## Швидкі посилання

- **APK:** [Releases](../../releases) → `bookvoices-*.apk` (або з приватного
  [bookvoices Releases](https://github.com/ech2corrosionrrd/bookvoices/releases),
  якщо тут ще немає артефактів)
- **AAB / FOSS APK:** `gradlew.bat :app:bundleRelease '-PreleaseTag=vX.Y.Z'` / `gradlew.bat :app:assembleRelease '-Pfoss=true'`
- **Privacy (EN, для Play Console):** [privacy-policy-en.md](https://github.com/ech2corrosionrrd/bookvoices-app/blob/main/docs/play-store/privacy-policy-en.md)
- **Issues:** [GitHub Issues](https://github.com/ech2corrosionrrd/bookvoices-app/issues)
