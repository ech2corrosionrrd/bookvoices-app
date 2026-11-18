# Примітки до випуску 1.18.0

**Підготовлено 13 листопада 2026, код доповнено 17–18 листопада 2026** (виправлення без нових функцій — у CHANGELOG). Тег, AAB і надсилання — не раніше **19 листопада 2026**
(7 днів 11700 у production без нових збоїв, див. ROADMAP), лише за командою власника.

versionName `1.18.0`, versionCode **11800**. Схема Room (**12**) і формат копії (**v4**) не змінилися.
У production зараз 11700 (1.17.0), тож примітки — «зміни з 1.17.0».

## Що змінилося з 1.17.0

Подробиці — у [`CHANGELOG.md`](../../CHANGELOG.md), розділ «1.18.0».

- **Автокопія в теку:** раз на день, 7 останніх файлів, «Зберегти зараз» (Free).
- **Назви розділів:** «Перейменувати розділ» довгим натисканням і «Пронумерувати»; нові назви синхронізуються через WebDAV (Free).
- **Історія:** серія днів прослуховування й рекорд, денна ціль, експорт історії в CSV (Free).
- **Повтор фрагмента A–B** у плеєрі (Pro, 6-та функція Pro).
- **«Повідомити про проблему»** у налаштуваннях: лист із журналом діагностики або Issue на GitHub; нічого не надсилається само.
- **Екран блокування на MIUI** (17 листопада 2026): назва книги й глава без «злиплого» тексту (`displayTitle` у метаданих сесії). У текст для Play не додано — ліміт 500 символів, а зміна непомітна поза Xiaomi.
- Оновлено політику конфіденційності (звіт про проблему, автокопія) — у публічному дзеркалі має з'явитися **до** надсилання в Play.

## Поле «Примітки до випуску» в Play Console ← використовувати

Довжини: en-US 420, uk 432, pl-PL 447, de-DE 444, es-ES 474, fr-FR 483, pt-BR 429 (ліміт 500).

```
<en-US>
New: a daily automatic backup to a folder you choose (keeps the last 7). Rename chapters or number them after confirming; new names sync over WebDAV. History shows your listening streak and a daily goal, and exports to CSV. Pro: A–B repeat in the player. "Report a problem" in Settings prepares an email with a diagnostic log; nothing is sent by itself. Back up straight to a file of your choice. Updated privacy policy.
</en-US>
<uk>
Нове: щоденна автокопія у вибрану теку (зберігає 7 останніх). «Перейменувати розділ» і «Пронумерувати»; нові назви синхронізуються через WebDAV. В «Історії» — серія днів прослуховування, ціль на день і експорт у CSV. Pro: повтор фрагмента A–B у плеєрі. «Повідомити про проблему» в налаштуваннях готує лист із журналом діагностики — нічого не надсилається само. Бекап можна зберегти просто у файл. Оновлено політику конфіденційності.
</uk>
<pl-PL>
Nowość: codzienna automatyczna kopia zapasowa do wybranego folderu (ostatnie 7). Zmiana nazw rozdziałów i „Ponumeruj”; nowe nazwy synchronizują się przez WebDAV. W historii: seria dni słuchania, cel dzienny i eksport do CSV. Pro: powtarzanie fragmentu A–B w odtwarzaczu. „Zgłoś problem” w ustawieniach przygotowuje e-mail z dziennikiem diagnostycznym — nic nie jest wysyłane samo. Kopię można zapisać do pliku. Zaktualizowana polityka prywatności.
</pl-PL>
<de-DE>
Neu: tägliche automatische Sicherung in einen Ordner deiner Wahl (die letzten 7). Kapitel umbenennen oder „Nummerieren“; neue Namen werden per WebDAV synchronisiert. Im Verlauf: Hörserie, Tagesziel und CSV-Export. Pro: A–B-Wiederholung im Player. „Problem melden“ in den Einstellungen bereitet eine E-Mail mit Diagnoseprotokoll vor – nichts wird automatisch gesendet. Sicherung direkt in eine Datei speichern. Datenschutzerklärung aktualisiert.
</de-DE>
<es-ES>
Novedades: copia de seguridad diaria automática en la carpeta que elijas (guarda las 7 últimas). Cambia el nombre de los capítulos o «Numerar»; los nombres se sincronizan por WebDAV. En el historial: racha de días de escucha, meta diaria y exportación a CSV. Pro: repetición A–B en el reproductor. «Informar de un problema» en Ajustes prepara un correo con el registro de diagnóstico; no se envía nada solo. Guarda la copia en un archivo. Política de privacidad actualizada.
</es-ES>
<fr-FR>
Nouveau : sauvegarde automatique quotidienne dans le dossier de votre choix (les 7 dernières). Renommez les chapitres ou « Numéroter » ; les noms se synchronisent via WebDAV. Dans l'historique : série de jours d'écoute, objectif quotidien et export CSV. Pro : répétition A–B dans le lecteur. « Signaler un problème » dans les réglages prépare un e-mail avec le journal de diagnostic ; rien n'est envoyé tout seul. Sauvegarde dans un fichier. Politique de confidentialité mise à jour.
</fr-FR>
<pt-BR>
Novo: backup automático diário na pasta que você escolher (mantém os 7 últimos). Renomeie capítulos ou use "Numerar"; os nomes sincronizam pelo WebDAV. No histórico: sequência de dias ouvindo, meta diária e exportação em CSV. Pro: repetição A–B no player. "Relatar um problema" nas configurações prepara um e-mail com o log de diagnóstico; nada é enviado sozinho. Salve o backup em um arquivo. Política de privacidade atualizada.
</pt-BR>
```

## Перевірка

```
python -X utf8 -c "import re;t=open('docs/play-store/release-notes-1.18.0.md',encoding='utf-8').read();[print(l,len(b.strip())) for l,b in re.findall(r'<([a-zA-Z-]+)>\n(.*?)\n</\1>',t,re.S)]"
```

## Перевірка перед випуском (13 листопада 2026, емулятор `BookVoices_A14`, Android 14)

> **Застаріла:** після неї в `master` увійшли «Зберегти у файл» для CSV і каталогу (13.11, 19:43) та
> `displayTitle` (17.11). Файли в `rel/mirror-1.18.0/` зібрано до цих змін — перед надсиланням
> 19 листопада 2026 зібрати AAB і APK заново й повторити перевірку оновлення 1.17 → 1.18.

- `ci-local.ps1` повний — зелений (юніт-тести, lint, FOSS, R8, AAB); інструментальні — **104 / 104**, без пропусків.
- **Оновлення поверх релізу:** релізний APK 1.17.0 (11700, зібраний з тега `v1.17.0`, підпис BookVoices) → демо-книга,
  відтворення до 0:05, закладка, тег → `adb install -r` релізного 1.18.0 (11800). Полиця, прогрес, закладка, тег і
  статистика «сьогодні» на місці.
- **Автокопія після R8:** вибір теки (SAF) → `bookvoices-backup-2026-11-13.json`, формат v4 (книга, закладка,
  поденна статистика, сесія); у журналі `FolderBackupWorker` з повним ім'ям класу, `SUCCESS` — правило `-keep` працює.
- **FOSS поверх Play-збірки** (той самий ключ): ставиться, дані зберігаються, Pro відкритий (без значка 💎).
- **Повтор A–B** у релізному FOSS-APK: відрізок 0:07–0:09, плеєр циклічно повертається до A (оновлення медіасесії
  з позицією 7015 мс кожні ~3,4 с), чип і підсвічування на смужці.
- `FATAL EXCEPTION` / ANR у журналі — 0.
- Не перевірено: купівля Pro і пейвол у Play-збірці (на емуляторі немає облікового запису Play), Bluetooth і Android
  Auto — лишаються в ручному чек-листі `TESTING.md` для телефона.
