# F-Droid та IzzyOnDroid

Поза Google Play BookVoices поширюється FOSS-збіркою (`-Pfoss=true`): без
`play-billing-ktx`, без дозволу `BILLING`, усі функції Pro відкриті. Код береться з
публічного дзеркала <https://github.com/ech2corrosionrrd/bookvoices-app>, яке готує
`scripts/prepare-public-mirror.ps1`.

## Що вже є в репозиторії

| Що | Де |
|---|---|
| FOSS-варіант збірки | `app/src/foss/`, прапорець `-Pfoss=true`; перевіряється в `scripts/ci-local.ps1` (повний прогін) |
| Опис для каталогів 7 мовами | `fastlane/metadata/android/<мова>/` — `title.txt`, `short_description.txt`, `full_description.txt`. Тексти — зі сторінки Play 1.17, абзац про Pro переписано під FOSS (функції Pro відкриті, покупка в Play — спосіб підтримати) |
| «Що нового» | `fastlane/metadata/android/{en-US,uk}/changelogs/<versionCode>.txt`, до 500 символів. Для інших мов каталоги беруть en-US |
| Скріншоти, іконка, банер | Не дублюються в приватному репо: `prepare-public-mirror.ps1` копіює їх у `fastlane/.../images/` з `docs/play-store/` (набір задає `$ScreenshotSet`) |
| APK у релізах дзеркала | `scripts/publish-mirror-release.ps1` збирає й підписує локально `bookvoices-X.Y.Z.apk` і `bookvoices-X.Y.Z-foss.apk` і створює реліз; `scripts/public-mirror/release.yml` лише перевіряє збірку тега на Linux |

## Стан на 13 листопада 2026

- Останній реліз у дзеркалі — **v1.12.0** (6 вересня 2026), останній push — 16 жовтня 2026.
  Подавати заявку можна лише після того, як у дзеркалі з'явиться реліз із `-foss.apk`
  (перший такий — 1.18.0).
- **Підпис — локально, без секретів у дзеркалі (рішення 13 листопада 2026).** Ключ підпису —
  він же ключ підписання застосунку в Google Play, тож у CI публічного репозиторію зі
  сторонніми Actions йому не місце. Підписані APK викладає
  `scripts/publish-mirror-release.ps1 -Tag vX.Y.Z -Publish` з ПК власника: збирає обидва
  варіанти, звіряє відбиток сертифіката, версію і відсутність `BILLING` у FOSS, створює
  реліз у `bookvoices-app`. `release.yml` дзеркала лише перевіряє, що тег збирається на
  Linux (F-Droid збиратиме так само). Пробний прогін для 1.18.0 (без `-Publish`) пройшов
  13 листопада 2026: FOSS 4,4 МБ, Play 4,7 МБ, 11800.

  За `docs/README.md` Play і GitHub-APK підписані тим самим ключем, тож FOSS-APK з тим самим
  `applicationId` ставиться поверх версії з Play і навпаки (зберігаючи полицю). IzzyOnDroid
  перевіряє, щоб підпис між версіями не змінювався, тож ключ для `-foss.apk` після першого
  релізу міняти не можна.
- Реліз дзеркала `v1.12.0` не видаляти: його відео (`bookvoices-foreground-service.mp4`)
  вказано в Play Console у декларації сервісу переднього плану.

## Порядок випуску 1.18.0 поза Play

1. Повний `scripts/ci-local.ps1` зелений (там уже є FOSS-крок).
2. За командою власника: тег `v1.18.0` у приватному репо, `prepare-public-mirror.ps1`,
   коміт і push дзеркала (без тега).
3. `.\scripts\publish-mirror-release.ps1 -Tag v1.18.0 -Publish` — реліз із підписаними
   `bookvoices-1.18.0.apk` і `bookvoices-1.18.0-foss.apk` (тег у дзеркалі створюється разом із релізом).
4. ~~**IzzyOnDroid:**~~ **не подаємо** — IzzyOnDroid відхиляє застосунки, створені повністю чи частково генеративним ШІ (див. [`izzyondroid-request.md`](izzyondroid-request.md)). Колишній план: issue на **Codeberg** <https://codeberg.org/IzzyOnDroid/repodata/issues>
   за шаблоном AppRequest (трекер на GitLab заархівовано). Готовий текст, передумови й
   розкриття допомоги ШІ (обов'язкове в шаблоні) — [`izzyondroid-request.md`](izzyondroid-request.md).
5. **F-Droid** — спершу перевірити чинну політику F-Droid щодо коду, написаного з ШІ (на листопад 2026 — лише чернетка «Interim Policy on Use of Generative AI»: вимога людського перегляду). Без явного дозволу не подавати. Далі (довше, від кількох тижнів): merge request у
   <https://gitlab.com/fdroid/fdroiddata> з файлом `metadata/app.bookvoices.yml`, чернетка нижче.

## Чернетка рецепта F-Droid

```yaml
Categories:
  - Multimedia
License: MIT
SourceCode: https://github.com/ech2corrosionrrd/bookvoices-app
IssueTracker: https://github.com/ech2corrosionrrd/bookvoices-app/issues
Changelog: https://github.com/ech2corrosionrrd/bookvoices-app/blob/main/CHANGELOG.md

AutoName: BookVoices

RepoType: git
Repo: https://github.com/ech2corrosionrrd/bookvoices-app.git

Builds:
  - versionName: 1.18.0
    versionCode: 11800
    commit: v1.18.0
    subdir: app
    gradle:
      - yes
    gradleprops:
      - foss=true
      - releaseTag=v1.18.0

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
CurrentVersion: 1.18.0
CurrentVersionCode: 11800
```

Що врахувати для F-Droid:

- `versionCode` не записано у файлі, він обчислюється з тега в кореневому
  `build.gradle.kts` (`X.Y.Z → XXYYZZ`). Тому в рецепті він заданий явно, а версія
  приходить через `gradleprops: releaseTag=…`. Автооновлення за тегами може вимагати
  `UpdateCheckData`; якщо рецензенти F-Droid попросять, простіше за все дописати в
  `build.gradle.kts` рядок-коментар із поточними `versionCode`/`versionName`.
- Перевірити, що `:baselineprofile` не тягне в збірку `:app` нічого невільного
  (`androidx.profileinstaller` вільний).
- Anti-features: жодних (ні трекерів, ні невільних залежностей; мережа — лише WebDAV
  на сервер користувача).
