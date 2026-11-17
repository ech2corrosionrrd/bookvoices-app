# Примітки до випуску 1.17.0

**Пакет підготовлено 8 листопада 2026.** Тег `v1.17.0` запушено 8 листопада 2026, GitHub Release створює
`release.yml`. **У Play:** AAB завантажено, випуск збережено; на перевірку надсилає власник (одна кнопка, див. внизу).

**Рішення: замінити 11600 на 11700, не чекаючи вердикту.** 11600 на перевірці з 8 листопада 2026, і 11700 містить
усе з 1.16.0 плюс виправлення. Буде одна перевірка замість двох поспіль, і користувачі з 1.14.0 одразу отримають
виправлену версію. Тому для Play взято примітки «з 1.14.0» (перший блок нижче).

versionName `1.17.0`, versionCode **11700**. Схема Room (**12**) і формат копії (**v4**) не змінилися.

## Що змінилося з 1.16.0

Подробиці — у [`CHANGELOG.md`](../../CHANGELOG.md), розділ «1.17.0».

- **«Поділитися журналом»** (Налаштування, внизу): локальна діагностика з власного logcat застосунку. Шляхи й адреси
  файлів, а з ними й назви книг, замінено на `<path>`. Нічого не надсилається само.
- **Ярлик «Продовжити …»** на іконці застосунку (довге натискання) — остання книга.
- **Плитка у швидких налаштуваннях:** грати/пауза.
- **TalkBack:** вкладки нижньої навігації отримали назви, раніше звучало лише «вкладка, 1 з 3».
- **Виправлено:** під час імпорту теки книга без обкладинки отримувала чужу з сусідньої теки без аудіо (наприклад,
  статистика іншого плеєра).
- **Виправлено:** запуск із ярлика, віджета чи плитки, коли застосунок не був запущений, відкривав книгу на паузі.

## Поле «Примітки до випуску» в Play Console — заміна 11600 (зміни з 1.14.0) ← використовувати

Довжини: en-US 426, uk 439, pl-PL 438, de-DE 428, es-ES 445, fr-FR 450, pt-BR 418 (ліміт 500).

```
<en-US>
New: a "Continue" shortcut on the app icon and a Quick Settings tile to play or pause. Sync between devices merges the same book instead of making a copy. Export a book summary, set your own cover, open .zip files from any file manager, manage what plays next. Share a diagnostic log from Settings. Better TalkBack support. Fixed: wrong covers after importing a folder, books starting paused, restoring Pro after reinstalling.
</en-US>
<uk>
Нове: ярлик «Продовжити» на іконці застосунку і плитка у швидких налаштуваннях: грати чи пауза. Синхронізація між пристроями зливає ту саму книгу замість копії. Конспект книги, власна обкладинка, відкриття .zip з будь-якого файлового менеджера, черга «далі». «Поділитися журналом» у налаштуваннях. Краща підтримка TalkBack. Виправлено: чужі обкладинки після імпорту теки, книга запускалася на паузі, відновлення Pro після перевстановлення.
</uk>
<pl-PL>
Nowość: skrót „Kontynuuj” na ikonie aplikacji i kafelek w szybkich ustawieniach do odtwarzania i pauzy. Synchronizacja łączy tę samą książkę z dwóch urządzeń zamiast tworzyć kopię. Podsumowanie książki, własna okładka, otwieranie plików .zip, kolejka „dalej”. Udostępnianie dziennika diagnostycznego. Lepsze wsparcie TalkBack. Poprawiono: cudze okładki po imporcie folderu, start książki w pauzie, przywracanie Pro po ponownej instalacji.
</pl-PL>
<de-DE>
Neu: Verknüpfung „Fortsetzen“ am App-Symbol und eine Kachel in den Schnelleinstellungen zum Abspielen oder Pausieren. Die Synchronisierung führt dasselbe Buch auf zwei Geräten zusammen statt es zu kopieren. Buchzusammenfassung, eigenes Cover, .zip-Dateien öffnen, Warteschlange. Diagnoseprotokoll teilen. Bessere TalkBack-Unterstützung. Behoben: falsche Cover nach Ordnerimport, Start im Pausenzustand, Pro nach Neuinstallation.
</de-DE>
<es-ES>
Novedades: acceso directo «Continuar» en el icono y un mosaico en los ajustes rápidos para reproducir o pausar. La sincronización une el mismo libro de dos dispositivos en vez de duplicarlo. Resumen del libro, portada propia, abrir archivos .zip, cola «a continuación». Compartir registro de diagnóstico. Mejor soporte de TalkBack. Corregido: portadas ajenas al importar una carpeta, libros que empezaban en pausa, restaurar Pro tras reinstalar.
</es-ES>
<fr-FR>
Nouveau : raccourci « Reprendre » sur l'icône et une tuile dans les réglages rapides pour lire ou mettre en pause. La synchronisation fusionne le même livre de deux appareils au lieu de le dupliquer. Résumé du livre, couverture perso, ouverture des fichiers .zip, file « à suivre ». Partage du journal de diagnostic. Meilleur support de TalkBack. Corrigé : mauvaises couvertures après import d'un dossier, livres lancés en pause, restauration de Pro.
</fr-FR>
<pt-BR>
Novo: atalho "Continuar" no ícone do app e um bloco nas configurações rápidas para tocar ou pausar. A sincronização junta o mesmo livro de dois aparelhos em vez de duplicá-lo. Resumo do livro, capa própria, abrir arquivos .zip, fila "a seguir". Compartilhar log de diagnóstico. Melhor suporte ao TalkBack. Corrigido: capas erradas após importar uma pasta, livros iniciando pausados, restauração do Pro após reinstalar.
</pt-BR>
```

## Запасний варіант: якщо 11600 встигне вийти раніше (зміни з 1.16.0)

Довжини перевірено скриптом (див. «Стан перевірки» нижче).

```
<en-US>
New: a "Continue" shortcut on the app icon and a Quick Settings tile to play or pause. Share a diagnostic log from Settings: it stays on your phone until you send it, with file paths and book titles removed. TalkBack now reads the names of the bottom tabs. Fixed: a book without its own cover could get one from a neighbouring folder during folder import. Fixed: starting from the shortcut, widget or tile while the app was closed opened the book paused.
</en-US>
<uk>
Нове: ярлик «Продовжити» на іконці застосунку і плитка у швидких налаштуваннях — грати чи пауза. «Поділитися журналом» у налаштуваннях: журнал лишається на телефоні, доки ви його не надішлете, без шляхів до файлів і назв книг. TalkBack читає назви нижніх вкладок. Виправлено: книга без власної обкладинки могла отримати чужу з сусідньої теки. Виправлено: запуск з ярлика, віджета чи плитки при закритому застосунку відкривав книгу на паузі.
</uk>
<pl-PL>
Nowość: skrót „Kontynuuj” na ikonie aplikacji i kafelek w szybkich ustawieniach — odtwarzanie lub pauza. Udostępnianie dziennika diagnostycznego w ustawieniach, bez ścieżek plików i tytułów książek. TalkBack czyta nazwy dolnych kart. Poprawiono: książka bez własnej okładki mogła dostać okładkę z sąsiedniego folderu. Poprawiono: start ze skrótu, widżetu lub kafelka przy zamkniętej aplikacji otwierał książkę wstrzymaną.
</pl-PL>
<de-DE>
Neu: Verknüpfung „Fortsetzen“ am App-Symbol und eine Kachel in den Schnelleinstellungen zum Abspielen oder Pausieren. Diagnoseprotokoll in den Einstellungen teilen, ohne Dateipfade und Buchtitel. TalkBack liest die Namen der unteren Tabs vor. Behoben: Ein Buch ohne eigenes Cover konnte das Cover eines Nachbarordners erhalten. Behoben: Start über Verknüpfung, Widget oder Kachel bei geschlossener App öffnete das Buch pausiert.
</de-DE>
<es-ES>
Novedades: acceso directo «Continuar» en el icono y un mosaico en los ajustes rápidos para reproducir o pausar. Comparte un registro de diagnóstico desde Ajustes, sin rutas de archivos ni títulos de libros. TalkBack lee los nombres de las pestañas inferiores. Corregido: un libro sin portada propia podía recibir la de una carpeta vecina. Corregido: iniciar desde el acceso directo, el widget o el mosaico con la app cerrada abría el libro en pausa.
</es-ES>
<fr-FR>
Nouveau : raccourci « Reprendre » sur l'icône et une tuile dans les réglages rapides pour lire ou mettre en pause. Partagez un journal de diagnostic depuis les réglages, sans chemins de fichiers ni titres de livres. TalkBack lit le nom des onglets du bas. Corrigé : un livre sans couverture pouvait recevoir celle d'un dossier voisin. Corrigé : lancer depuis le raccourci, le widget ou la tuile, appli fermée, ouvrait le livre en pause.
</fr-FR>
<pt-BR>
Novo: atalho "Continuar" no ícone do app e um bloco nas configurações rápidas para tocar ou pausar. Compartilhe um log de diagnóstico nas configurações, sem caminhos de arquivos nem títulos de livros. O TalkBack lê os nomes das abas inferiores. Corrigido: um livro sem capa própria podia receber a capa de uma pasta vizinha. Corrigido: iniciar pelo atalho, widget ou bloco com o app fechado abria o livro pausado.
</pt-BR>
```

## Нотатки для GitHub Release `v1.17.0`

```
Diagnostics: "Share diagnostic log" in Settings — the app's own logcat (BookVoices tag and its own crashes), header with version and device, file paths and content URIs replaced with <path>; shared via the system sheet, nothing is sent automatically.
Launcher: dynamic "Continue <book>" shortcut for the current/last book; removed when the last book is forgotten.
Quick Settings: play/pause tile, active while playing, book title as subtitle on Android 10+.
Accessibility: bottom navigation tabs expose their names to TalkBack (labels were reported with zero bounds).
Fix: folder import no longer lends a cover from a sibling folder without audio; a cover is borrowed only from an ancestor folder that holds this single book.
Fix: cold start from the shortcut, widget or tile no longer ends paused (startup restore re-checks under the prepare lock).
Debug builds now use the app.bookvoices.debug package and install next to the Play version.
No schema or backup format changes: Room 12, backup v4.
```

## Стан перевірки перед випуском (8 листопада 2026)

| Перевірка | Результат |
|---|---|
| `scripts/ci-local.ps1` (кодування, юніт-тести, lint, R8, AAB) | зелений |
| Інструментальні тести (AVD Medium_Phone, API 30) | зелені, 104 з 104 |
| `TabAccessibilityTest` (AVD BookVoices_A14, API 34) | зелений |
| AAB `-PreleaseTag=v1.17.0` | versionCode 11700, versionName 1.17.0 (у dex — `1.17.0` без `-dev`); `jarsigner -verify` дійсний; сертифікат той самий, що в 1.16.0 (SHA-256 `34:83:20:68:…:50:85`) |
| Довжини приміток 7 мовами | 413–454 з 500 |
| На живому пристрої (Redmi 2201116TG, Android 13, через adb) | ярлик, плитка, журнал, імпорт без чужих обкладинок, холодний старт — так; Android 14 (емулятор) — плитка й ярлик так |

AAB і `mapping.txt` лежать локально в `rel/release-1.17.0/` (поза git): `bookvoices-1.17.0-11700.aab`
(SHA-256 `f75d2a528a6101a7865986614629a362ca8683dbe2e75dc582da00373c71fa48`) і `mapping-1.17.0-11700.txt`
(SHA-256 `3bc659362b6144212a26062ee9c2d89555754d92bd814d2217c53985bd0fa2fd`).

У PowerShell аргумент тега брати в лапки: `"-PreleaseTag=v1.17.0"`. Без лапок PowerShell ріже його по крапці до
`v1`, і збірка падає з «не схожий на vX.Y.Z».

## Що лишилося (власник, 1 хвилина)

Стан на 8 листопада 2026: AAB 11700 завантажено в Play, примітки 7 мовами вставлено, повний випуск (100 %)
збережено. На сторінці «Огляд публікації» він стоїть у «Зміни, які ще не надіслано на перевірку». Останню кнопку
класифікатор дозволів Claude Code не дає натиснути агенту.

1. Play Console → **Огляд публікації** → **«Надіслати 1 зміну на перевірку»**. 11700 замінить 11600, яка зараз на
   перевірці. Сторінка магазину 7 мовами лишається на перевірці окремо.
2. За бажанням: App Bundle Explorer → 11700 → «Завантаження» → «Файл зіставлення ReTrace» → вибрати
   `rel/release-1.17.0/mapping-1.17.0-11700.txt` (72 МБ, агент такий файл не завантажить: ліміт 10 МБ).

**Чого не обіцяти:** Android Auto в машині не перевірено.
