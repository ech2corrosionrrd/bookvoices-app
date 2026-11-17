# Заявка в IzzyOnDroid — НЕ ПОДАВАТИ

> **Рішення 13 листопада 2026: заявку не подаємо.** Перевірено перед поданням:
>
> - [App Inclusion Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/) IzzyOnDroid:
>   «We are strongly opposed to apps which are fully or in part created by generative AI tools»,
>   «Vibe-coded apps will be rejected»; за виявлене приховування — відмова. Значну частину коду
>   BookVoices написано з Claude Code, тож чесна заявка буде відхилена, а нечесну подавати не можна.
> - Умови використання Codeberg (зміна 2026 р., §2 п.7): заборонено публікувати проєкти, що
>   здебільшого складаються з коду генеративних ШІ (Claude, Codex тощо); порушення — видалення й
>   попередження, повторне — блокування акаунта. Акаунт власника на Codeberg (`w3ir`) не ризикуємо.
>
> Текст нижче лишено для історії — на випадок, якщо правила зміняться.


Підготовлено 13 листопада 2026 за чинними правилами IzzyOnDroid:
[App Inclusion Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/),
[New App Inclusions](https://izzyondroid.org/contributing/NewAppInclusions/). Старий трекер
на GitLab заархівовано — заявки тепер **на Codeberg**:
<https://codeberg.org/IzzyOnDroid/repodata/issues> → *New issue* → шаблон **AppRequest**.
Потрібен обліковий запис на Codeberg (реєструє власник сам).

## Перш ніж подавати (усе — після випуску 1.18.0)

- [ ] У публічному дзеркалі `bookvoices-app` є тег `v1.18.0` і реліз із **підписаним**
      `bookvoices-1.18.0-foss.apk` (`scripts/publish-mirror-release.ps1 -Tag v1.18.0 -Publish`).
- [ ] У дзеркалі є тека `fastlane/metadata/android/` зі скріншотами (її докладає
      `prepare-public-mirror.ps1`; перевірити на GitHub, що `images/phoneScreenshots/` не порожні).
- [ ] Відкрити <https://github.com/ech2corrosionrrd/bookvoices-app/releases/latest> без входу
      в GitHub — реліз видно, обидва APK качаються.

Що вже перевірено в FOSS-APK 1.18.0 (13 листопада 2026): немає `com.google.android.gms`,
Firebase, `billingclient`, `datatransport`, аналітики; немає `android:debuggable` і
`testOnly`; відкритий HTTP заборонено (`cleartextTrafficPermitted="false"`); 4,4 МБ (ліміт 30);
підпис — релізний ключ BookVoices; ліцензія MIT (OSI).

## Чесно про «AI» — вирішує власник

Шаблон вимагає розкрити використання ШІ-інструментів і підтвердити дві речі від імені
розробника. Значну частину коду й документації BookVoices написано з Claude Code
(Anthropic). Блок нижче заповнено як **чернетку**: рівень допомоги і галочки
«AI Accountability» власник ставить сам — це його заява, а не моя. Приховувати
допомогу ШІ не можна: IzzyOnDroid розглядає це як порушення правил подання.

---

## Назва issue

```
[AppRequest] BookVoices
```

## Текст issue (англійською)

```markdown
### Guidelines

- [ ] I am the developer of the app.
- [ ] The app complies with the App Inclusion Policy.
- [ ] The app is not already listed in the repo or issue tracker.
- [ ] The Fastlane folder is available in the app's repo.

### Link to the source code

https://github.com/ech2corrosionrrd/bookvoices-app

### Link to app in another app store

https://play.google.com/store/apps/details?id=app.bookvoices

### License used

MIT

### Categories

Multimedia

### Summary

Offline audiobook player for your own files: M4B, MP3, chapters, bookmarks, sleep timer. No account, no ads.

### Description

BookVoices plays audiobooks you already own. Add a folder of mp3, m4b, ogg, opus or flac files
and it resumes exactly where you stopped: chapters from m4b, ID3 and CUE, bookmarks with notes,
sleep timer (end of chapter, shake to extend), A–B repeat, series and tags, Android Auto, widget,
Quick Settings tile. Backups go to a folder you choose or to your own WebDAV server; otherwise
the app never goes online. No account, no analytics, no trackers, no ads. 7 UI languages.

### Build instructions

Gradle, JDK 17: `./gradlew :app:assembleRelease -Pfoss=true -PreleaseTag=vX.Y.Z`
(BUILD.md in the repo; CI check: .github/workflows/release.yml).

### Assistance Level

<!-- власник обирає варіант із шаблону; чернетка: -->
Substantial

### "AI" Tool(s)

Claude Code (Anthropic)

### What did the tools help with?

<!-- чернетка, власник уточнює -->
Writing and refactoring Kotlin code, tests and documentation under the developer's direction.

### AI Accountability

- [ ] The human developer(s) reviewed and edited all "AI"-generated outputs
- [ ] The human developer(s) ran manual tests and manually verified all changes

### Further Notices

Each GitHub release contains two APKs. Please use only the FOSS one:
`bookvoices-X.Y.Z-foss.apk` (regex: `bookvoices-.*-foss\.apk`).

- The FOSS build (`-Pfoss=true`) has no Google Play Billing library and no BILLING permission;
  all features that are a one-time purchase on Google Play are unlocked in it.
- The other APK (`bookvoices-X.Y.Z.apk`) is the Google Play variant with Play Billing — not meant for IzzyOnDroid.
- Both are signed with the same release key (certificate SHA-256
  34:83:20:68:EA:D9:34:72:3A:F5:9A:4C:01:07:27:AB:41:7C:B7:E9:2D:88:E0:FE:F9:C5:9B:DA:8D:14:50:85).
- Tags are `vX.Y.Z` = versionName with a `v`; versionCode is `XXYYZZ` (1.18.0 → 11800).
- Network: INTERNET is used only for the user's own WebDAV server (HTTPS only, cleartext disabled).
```

## Після подання

- Записати номер issue в ROADMAP («Далі — дистрибуція») і в `docs/fdroid.md`.
- Відповідати на питання рецензента в тому самому issue (зазвичай про вибір APK, ключ
  і fastlane). Звичайний строк — від кількох днів до кількох тижнів.
- Коли застосунок з'явиться в IzzyOnDroid — оновити README (бейдж/посилання) і
  `docs/outreach.md` (у постах можна давати посилання на IzzyOnDroid).
