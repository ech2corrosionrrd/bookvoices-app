# Сторінка Play під 1.17.0 — усі 7 мов

**Підготовлено й внесено в Console як чернетку 8 листопада 2026** (усі 7 мов: опис і 8 скріншотів телефона; іконка й банер без змін). Чекає на «Надіслати зміни на перевірку» разом із випуском 11700. На основі [`listing-1.16-all-languages.md`](listing-1.16-all-languages.md)
(те, що на перевірці разом з 11600). Зміни: у розділі про відтворення кожної мови — рядок про ярлик «Продовжити» і
плитку у швидких налаштуваннях (1.17.0). Функції 1.18.0 (автокопія, назви розділів, «Повідомити про проблему») сюди
не внесено — до їхнього виходу.

Скріншоти для всіх 7 мов — [`screenshots/v1.17-*/`](screenshots/) (див. README там).

Довжини перевірено скриптом `check-listing-lengths.py` (параметр — шлях до цього файла).

---

## en-US

**Short:** Audiobook player for your own files: M4B, MP3, bookmarks, sleep timer. No ads.

```text
BookVoices is an audiobook player for your own collection. Add a folder of mp3, m4b, ogg or flac files and pick up exactly where you left off. No account, no ads, no subscription; your files stay on your phone.

LISTENING
• mp3, m4b, ogg, flac and opus; chapters from m4b, ID3 and CUE, and nested CD1/CD2 folders
• Resume where you stopped, smart rewind after a pause
• Playback speed, skip controls, optional headset button gestures
• Sleep timer: minutes, end of chapter or book, shake to extend; a night schedule starts it for you
• One-tap bookmark from the notification shade; notes on bookmarks and search across them
• Resume after your headphones reconnect (optional)
• Up Next queue for back-to-back books
• Background playback, home-screen widget, lock-screen and notification controls
• "Continue" shortcut on the app icon and a Quick Settings tile to play or pause

LIBRARY
• Shelf with covers, search, tags, series and pinned favorites
• Grid or compact list view, comfortable even with a hundred books
• Import a folder, single files or a ZIP archive (open it straight from your file manager); set your own cover
• Parses "Author - Title" from folder names, with an author screen
• Fast multi-word search that ignores case, diacritics and apostrophes
• Automatically picks up new books from folders you choose
• Flags books whose files went missing

STATS AND DATA
• Listening history with week, month and year charts
• Progress backup to JSON or to your own WebDAV server; sync between phone and tablet without duplicate books
• Export your catalog, bookmarks and a per-book summary as Markdown

COMFORT
• Night, Paper and true-black OLED themes
• Large-font and tablet friendly layouts
• 7 interface languages: English, Українська, Polski, Deutsch, Español, Français, Português (Brasil)

Privacy. No analytics, no trackers, no sign-up. The app works offline; the network is used only if you turn on WebDAV backup yourself.

BookVoices Pro is a one-time purchase, no subscription: silence and pause skipping, hardware Volume Boost (+6 dB), voice equalizer with pitch correction, auto-bookmark on Bluetooth disconnect, and per-book character lists. Everything else is free, with no ads.

BookVoices doesn't sell or stream books: it plays the ones you already own.
```

**What's new (1.16.0):**

```text
Sync between devices is smarter: the same book imported on two devices now merges progress, bookmarks and tags instead of duplicating. WebDAV syncs a minute after you pause. Export a book summary to Markdown, set your own cover or take it from a ZIP, open .zip files from any file manager, manage the Up Next queue. Smart rewind works from every control; fixed Pro restore after reinstall; orphaned files are cleaned up; imports survive leaving the screen.
```

---

## uk

**Short:** Плеєр аудіокниг для ваших файлів: M4B, MP3, закладки, таймер сну. Без реклами.

```text
BookVoices — плеєр аудіокниг для вашої власної колекції. Додайте теку з mp3, m4b, ogg чи flac — і слухайте з того місця, де зупинилися. Без акаунта, без реклами, без підписки; файли лишаються на вашому телефоні.

СЛУХАННЯ
• Формати mp3, m4b, ogg, flac, opus; глави з m4b, ID3 і CUE та вкладені теки CD1/CD2
• Продовження з того ж місця, розумне відмотування після паузи
• Швидкість відтворення, перемотування, жести кнопки гарнітури (за бажанням)
• Таймер сну: хвилини, до кінця глави чи книги, струсіть, щоб продовжити; нічний таймер за розкладом вмикається сам
• Закладка одним дотиком зі шторки сповіщень; нотатки до закладок і пошук по них
• Продовження після повернення навушників (за бажанням)
• Черга «далі» для кількох книг поспіль
• Фонове відтворення, віджет на головному екрані, керування зі шторки й екрана блокування
• Ярлик «Продовжити» на іконці застосунку і плитка у швидких налаштуваннях: грати чи пауза

БІБЛІОТЕКА
• Полиця з обкладинками, пошуком, тегами, серіями та закріпленням улюблених
• Сітка або компактний список — зручно навіть для сотні книг
• Імпорт теки, окремих файлів чи ZIP-архіву (його можна відкрити просто з файлового менеджера); власна обкладинка
• Розпізнавання «Автор — Назва» з імен тек, екран автора
• Швидкий пошук за кількома словами: незалежно від регістру, діакритики й апострофів
• Автоматично додає нові книги з вказаних вами тек
• Позначає книги, чиї файли зникли

СТАТИСТИКА Й ДАНІ
• Історія прослуховування з графіком за тиждень, місяць або рік
• Резервна копія прогресу в JSON або на ваш сервер WebDAV; синхронізація між телефоном і планшетом без дублікатів книг
• Експорт каталогу, закладок і конспекту окремої книги в Markdown

ЗРУЧНІСТЬ
• Теми «Ніч», «Папір» і справжній чорний OLED
• Великий шрифт і планшети: інтерфейс не ламається
• 7 мов інтерфейсу: українська, English, Polski, Deutsch, Español, Français, Português (Brasil)

Приватність. Жодної аналітики, трекерів і реєстрації. Застосунок працює без мережі; мережа потрібна лише якщо ви самі ввімкнете резервну копію WebDAV.

BookVoices Pro — одноразова покупка, без підписки: пропуск тиші та пауз, апаратний Volume Boost (+6 dB), голосовий еквалайзер із корекцією тону, автозакладка при розриві Bluetooth, список персонажів книги. Усе інше безкоштовне й без реклами.

BookVoices не продає і не стримить книги: він грає ті, що вже є у вас.
```

**Що нового (1.16.0):**

```text
Розумніша синхронізація між пристроями: та сама книга, імпортована на двох пристроях, зливає прогрес, закладки й мітки замість дубліката. WebDAV синхронізується за хвилину після паузи. Конспект книги в Markdown, власна обкладинка чи з ZIP, відкриття .zip із файлових менеджерів, керування чергою. Розумний відкат зі всіх керувань; виправлено відновлення Pro; осиротілі файли прибираються; імпорт не переривається.
```

---

## pl-PL

**Short:** Odtwarzacz audiobooków z Twoich plików: M4B, MP3, zakładki, timer. Bez reklam.

```text
BookVoices to odtwarzacz audiobooków dla Twojej własnej kolekcji. Dodaj folder z plikami mp3, m4b, ogg lub flac i słuchaj dokładnie od miejsca, w którym skończyłeś. Bez konta, bez reklam, bez subskrypcji; pliki zostają na Twoim telefonie.

SŁUCHANIE
• mp3, m4b, ogg, flac i opus; rozdziały z m4b, ID3 i CUE oraz zagnieżdżone foldery CD1/CD2
• Wznawianie od miejsca zatrzymania, inteligentne cofanie po pauzie
• Prędkość odtwarzania, przewijanie, opcjonalne gesty przycisku słuchawek
• Wyłącznik czasowy: minuty, koniec rozdziału lub książki, potrząśnij, aby przedłużyć; nocny harmonogram włącza go sam
• Zakładka jednym dotknięciem z panelu powiadomień; notatki do zakładek i wyszukiwanie w nich
• Wznawianie po ponownym podłączeniu słuchawek (opcjonalnie)
• Kolejka odtwarzania dla kilku książek z rzędu
• Odtwarzanie w tle, widżet na ekranie głównym, sterowanie z ekranu blokady i powiadomień
• Skrót „Kontynuuj” na ikonie aplikacji i kafelek w szybkich ustawieniach do odtwarzania i pauzy

BIBLIOTEKA
• Półka z okładkami, wyszukiwaniem, tagami, seriami i przypiętymi ulubionymi
• Siatka lub kompaktowa lista — wygodnie nawet przy setce książek
• Import folderu, pojedynczych plików lub archiwum ZIP (otwórz je prosto z menedżera plików); własna okładka
• Rozpoznaje „Autor - Tytuł” z nazw folderów, ekran autora
• Szybkie wyszukiwanie wielu słów bez względu na wielkość liter, znaki diakrytyczne i apostrofy
• Automatycznie dodaje nowe książki z wybranych folderów
• Oznacza książki, których pliki zniknęły

STATYSTYKI I DANE
• Historia słuchania z wykresami tygodniowymi, miesięcznymi i rocznymi
• Kopia zapasowa postępu do JSON lub na własny serwer WebDAV; synchronizacja telefonu z tabletem bez duplikatów książek
• Eksport katalogu, zakładek i podsumowania książki do Markdown

WYGODA
• Motywy Noc, Papier i całkowicie czarny OLED
• Układ przyjazny dużej czcionce i tabletom
• 7 języków interfejsu: English, Українська, Polski, Deutsch, Español, Français, Português (Brasil)

Prywatność. Bez analityki, trackerów i rejestracji. Aplikacja działa offline; sieć jest używana tylko wtedy, gdy sam włączysz kopię zapasową WebDAV.

BookVoices Pro to jednorazowy zakup, bez subskrypcji: pomijanie ciszy i pauz, sprzętowe wzmocnienie głośności (+6 dB), korektor głosu z korekcją wysokości tonu, automatyczna zakładka po rozłączeniu Bluetooth i lista postaci dla każdej książki. Cała reszta jest darmowa i bez reklam.

BookVoices nie sprzedaje ani nie streamuje książek: odtwarza te, które już masz.
```

**Co nowego (1.16.0):**

```text
Mądrzejsza synchronizacja między urządzeniami: ta sama książka zaimportowana na dwóch urządzeniach łączy postęp, zakładki i tagi zamiast tworzyć duplikat. WebDAV synchronizuje się minutę po pauzie. Eksport podsumowania książki do Markdown, własna okładka lub okładka z ZIP, otwieranie plików .zip z menedżera plików, zarządzanie kolejką. Inteligentne cofanie działa z każdego sterowania; naprawione przywracanie Pro po reinstalacji; osierocone pliki są usuwane; import nie przerywa się.
```

---

## de-DE

**Short:** Hörbuch-Player für deine eigenen Dateien: M4B, MP3, Lesezeichen, Sleeptimer.

```text
BookVoices ist ein Hörbuch-Player für deine eigene Sammlung. Füge einen Ordner mit mp3-, m4b-, ogg- oder flac-Dateien hinzu und höre genau dort weiter, wo du aufgehört hast. Kein Konto, keine Werbung, kein Abo; deine Dateien bleiben auf deinem Handy.

HÖREN
• mp3, m4b, ogg, flac und opus; Kapitel aus m4b, ID3 und CUE sowie verschachtelte CD1/CD2-Ordner
• Fortsetzen an der gleichen Stelle, intelligentes Zurückspulen nach einer Pause
• Wiedergabegeschwindigkeit, Spultasten, optionale Gesten mit der Headset-Taste
• Sleeptimer: Minuten, Ende des Kapitels oder Buchs, Schütteln zum Verlängern; ein Nachtplan startet ihn automatisch
• Lesezeichen mit einem Tipp aus der Benachrichtigungsleiste; Notizen zu Lesezeichen und Suche darin
• Fortsetzen, wenn die Kopfhörer wieder verbunden sind (optional)
• Warteschlange für mehrere Bücher hintereinander
• Wiedergabe im Hintergrund, Widget auf dem Startbildschirm, Steuerung auf dem Sperrbildschirm und in der Benachrichtigung
• Verknüpfung „Fortsetzen“ am App-Symbol und Kachel in den Schnelleinstellungen zum Abspielen oder Pausieren

BIBLIOTHEK
• Regal mit Covern, Suche, Tags, Reihen und angehefteten Favoriten
• Raster oder kompakte Liste, angenehm selbst bei hundert Büchern
• Import eines Ordners, einzelner Dateien oder eines ZIP-Archivs (direkt aus dem Dateimanager öffnen); eigenes Cover
• Erkennt „Autor - Titel“ aus Ordnernamen, mit Autorenansicht
• Schnelle Suche nach mehreren Wörtern, unabhängig von Groß-/Kleinschreibung, diakritischen Zeichen und Apostrophen
• Übernimmt neue Bücher automatisch aus Ordnern deiner Wahl
• Markiert Bücher, deren Dateien verschwunden sind

STATISTIK UND DATEN
• Hörverlauf mit Wochen-, Monats- und Jahresdiagrammen
• Sicherung des Fortschritts als JSON oder auf deinem eigenen WebDAV-Server; Synchronisierung zwischen Handy und Tablet ohne doppelte Bücher
• Export von Katalog, Lesezeichen und einer Buchzusammenfassung als Markdown

KOMFORT
• Themes Nacht, Papier und echtes Schwarz für OLED
• Für große Schrift und Tablets geeignete Layouts
• 7 Sprachen: English, Українська, Polski, Deutsch, Español, Français, Português (Brasil)

Datenschutz. Keine Analyse, keine Tracker, keine Anmeldung. Die App funktioniert offline; das Netzwerk wird nur genutzt, wenn du die WebDAV-Sicherung selbst einschaltest.

BookVoices Pro ist ein einmaliger Kauf, kein Abo: Überspringen von Stille und Pausen, Hardware-Lautstärkeverstärkung (+6 dB), Sprach-Equalizer mit Tonhöhenkorrektur, automatisches Lesezeichen beim Trennen von Bluetooth und Figurenlisten pro Buch. Alles andere ist kostenlos und ohne Werbung.

BookVoices verkauft oder streamt keine Bücher: Es spielt die, die du bereits besitzt.
```

**Neuigkeiten (1.16.0):**

```text
Klügere Synchronisierung zwischen Geräten: Dasselbe Buch, auf zwei Geräten importiert, führt Fortschritt, Lesezeichen und Tags zusammen, statt doppelt aufzutauchen. WebDAV synchronisiert eine Minute nach der Pause. Buchzusammenfassung als Markdown, eigenes Cover oder Cover aus ZIP, .zip-Dateien aus dem Dateimanager öffnen, Warteschlange verwalten. Intelligentes Zurückspulen von jeder Steuerung; Pro-Wiederherstellung behoben; verwaiste Dateien werden entfernt; Import läuft weiter.
```

---

## es-ES

**Short:** Reproductor de audiolibros de tus archivos: M4B, MP3, marcadores. Sin anuncios.

```text
BookVoices es un reproductor de audiolibros para tu propia colección. Añade una carpeta con archivos mp3, m4b, ogg o flac y continúa justo donde lo dejaste. Sin cuenta, sin anuncios, sin suscripción; tus archivos se quedan en tu teléfono.

ESCUCHAR
• mp3, m4b, ogg, flac y opus; capítulos de m4b, ID3 y CUE, y carpetas anidadas CD1/CD2
• Continúa donde lo dejaste, retroceso inteligente tras una pausa
• Velocidad de reproducción, saltos, gestos opcionales con el botón de los auriculares
• Temporizador de apagado: minutos, fin del capítulo o del libro, agita para alargarlo; un horario nocturno lo activa solo
• Marcador con un toque desde el panel de notificaciones; notas en los marcadores y búsqueda entre ellos
• Reanuda al volver a conectar los auriculares (opcional)
• Cola de reproducción para varios libros seguidos
• Reproducción en segundo plano, widget en la pantalla de inicio, controles en la pantalla de bloqueo y en la notificación
• Acceso directo «Continuar» en el icono y mosaico en los ajustes rápidos para reproducir o pausar

BIBLIOTECA
• Estantería con portadas, búsqueda, etiquetas, series y favoritos fijados
• Vista de cuadrícula o lista compacta, cómoda incluso con cien libros
• Importa una carpeta, archivos sueltos o un archivo ZIP (ábrelo directamente desde tu gestor de archivos); portada propia
• Reconoce «Autor - Título» en los nombres de carpeta, con pantalla de autor
• Búsqueda rápida de varias palabras sin distinguir mayúsculas, tildes ni apóstrofos
• Añade automáticamente libros nuevos de las carpetas que elijas
• Marca los libros cuyos archivos han desaparecido

ESTADÍSTICAS Y DATOS
• Historial de escucha con gráficos semanales, mensuales y anuales
• Copia de seguridad del progreso en JSON o en tu propio servidor WebDAV; sincronización entre teléfono y tableta sin libros duplicados
• Exporta tu catálogo, marcadores y un resumen de cada libro en Markdown

COMODIDAD
• Temas Noche, Papel y negro puro OLED
• Diseño cómodo con letra grande y en tabletas
• 7 idiomas: English, Українська, Polski, Deutsch, Español, Français, Português (Brasil)

Privacidad. Sin analíticas, sin rastreadores, sin registro. La app funciona sin conexión; la red solo se usa si activas tú mismo la copia de seguridad WebDAV.

BookVoices Pro es una compra única, sin suscripción: salto de silencios y pausas, amplificación de volumen por hardware (+6 dB), ecualizador de voz con corrección de tono, marcador automático al desconectar el Bluetooth y lista de personajes por libro. Todo lo demás es gratis y sin anuncios.

BookVoices no vende ni transmite libros: reproduce los que ya tienes.
```

**Novedades (1.16.0):**

```text
Sincronización más inteligente entre dispositivos: el mismo libro importado en dos dispositivos une progreso, marcadores y etiquetas en lugar de duplicarse. WebDAV sincroniza un minuto después de pausar. Resumen del libro en Markdown, portada propia o desde un ZIP, abre archivos .zip desde el gestor de archivos, gestiona la cola. Retroceso inteligente desde cualquier control; corregida la restauración de Pro; se limpian archivos huérfanos; la importación sigue al salir.
```

---

## fr-FR

**Short:** Lecteur de livres audio pour vos fichiers : M4B, MP3, signets. Sans publicité.

```text
BookVoices est un lecteur de livres audio pour votre propre collection. Ajoutez un dossier de fichiers mp3, m4b, ogg ou flac et reprenez exactement là où vous vous êtes arrêté. Sans compte, sans publicité, sans abonnement ; vos fichiers restent sur votre téléphone.

ÉCOUTE
• mp3, m4b, ogg, flac et opus ; chapitres depuis m4b, ID3 et CUE, et dossiers imbriqués CD1/CD2
• Reprise là où vous vous êtes arrêté, retour en arrière intelligent après une pause
• Vitesse de lecture, sauts, gestes facultatifs avec le bouton du casque
• Minuteur de sommeil : minutes, fin du chapitre ou du livre, secouez pour prolonger ; un horaire de nuit le lance tout seul
• Signet en un geste depuis le volet des notifications ; notes sur les signets et recherche parmi eux
• Reprise au retour du casque (facultatif)
• File d'attente pour enchaîner plusieurs livres
• Lecture en arrière-plan, widget d'écran d'accueil, commandes sur l'écran de verrouillage et dans la notification
• Raccourci « Reprendre » sur l'icône et tuile dans les réglages rapides pour lire ou mettre en pause

BIBLIOTHÈQUE
• Étagère avec couvertures, recherche, tags, séries et favoris épinglés
• Grille ou liste compacte, confortable même avec une centaine de livres
• Import d'un dossier, de fichiers isolés ou d'une archive ZIP (ouvrez-la directement depuis votre gestionnaire de fichiers) ; couverture personnalisée
• Reconnaît « Auteur - Titre » dans les noms de dossiers, avec un écran auteur
• Recherche rapide sur plusieurs mots, sans tenir compte de la casse, des accents ni des apostrophes
• Ajoute automatiquement les nouveaux livres des dossiers choisis
• Signale les livres dont les fichiers ont disparu

STATISTIQUES ET DONNÉES
• Historique d'écoute avec graphiques par semaine, mois et année
• Sauvegarde de la progression en JSON ou sur votre propre serveur WebDAV ; synchronisation entre téléphone et tablette sans livres en double
• Export du catalogue, des signets et d'un résumé de livre en Markdown

CONFORT
• Thèmes Nuit, Papier et noir pur OLED
• Affichage adapté aux grandes polices et aux tablettes
• 7 langues : English, Українська, Polski, Deutsch, Español, Français, Português (Brasil)

Confidentialité. Aucune analyse, aucun traceur, aucune inscription. L'app fonctionne hors ligne ; le réseau n'est utilisé que si vous activez vous-même la sauvegarde WebDAV.

BookVoices Pro est un achat unique, sans abonnement : saut des silences et des pauses, amplification matérielle du volume (+6 dB), égaliseur vocal avec correction de hauteur, signet automatique à la déconnexion Bluetooth et liste des personnages par livre. Tout le reste est gratuit et sans publicité.

BookVoices ne vend ni ne diffuse de livres : il lit ceux que vous possédez déjà.
```

**Nouveautés (1.16.0) :**

```text
Synchronisation plus intelligente : un même livre importé sur deux appareils fusionne progression, signets et tags au lieu d'être dupliqué. WebDAV se synchronise une minute après la pause. Résumé du livre en Markdown, couverture personnalisée ou tirée d'un ZIP, ouverture des .zip depuis le gestionnaire de fichiers, gestion de la file d'attente. Retour en arrière intelligent depuis toutes les commandes ; restauration de Pro corrigée ; fichiers orphelins nettoyés ; l'import continue.
```

---

## pt-BR

**Short:** Player de audiolivros dos seus arquivos: M4B, MP3, marcadores. Sem anúncios.

```text
BookVoices é um player de audiolivros para a sua própria coleção. Adicione uma pasta com arquivos mp3, m4b, ogg ou flac e continue exatamente de onde parou. Sem conta, sem anúncios, sem assinatura; seus arquivos ficam no seu celular.

OUVIR
• mp3, m4b, ogg, flac e opus; capítulos de m4b, ID3 e CUE, e pastas aninhadas CD1/CD2
• Continua de onde você parou, retrocesso inteligente após uma pausa
• Velocidade de reprodução, avanço e retrocesso, gestos opcionais no botão do fone
• Temporizador para dormir: minutos, fim do capítulo ou do livro, agite para estender; um horário noturno o ativa sozinho
• Marcador com um toque na área de notificações; notas nos marcadores e busca entre eles
• Retoma quando o fone se reconecta (opcional)
• Fila Próximos para ouvir vários livros em sequência
• Reprodução em segundo plano, widget na tela inicial, controles na tela de bloqueio e na notificação
• Atalho "Continuar" no ícone do app e bloco nas configurações rápidas para tocar ou pausar

BIBLIOTECA
• Estante com capas, busca, tags, séries e favoritos fixados
• Grade ou lista compacta, confortável mesmo com cem livros
• Importe uma pasta, arquivos avulsos ou um arquivo ZIP (abra direto do seu gerenciador de arquivos); capa personalizada
• Reconhece "Autor - Título" nos nomes das pastas, com tela de autor
• Busca rápida por várias palavras, sem diferenciar maiúsculas, acentos e apóstrofos
• Adiciona automaticamente livros novos das pastas que você escolher
• Sinaliza livros cujos arquivos sumiram

ESTATÍSTICAS E DADOS
• Histórico de escuta com gráficos por semana, mês e ano
• Backup do progresso em JSON ou no seu próprio servidor WebDAV; sincronização entre celular e tablet sem livros duplicados
• Exporte catálogo, marcadores e um resumo de cada livro em Markdown

CONFORTO
• Temas Noite, Papel e preto puro OLED
• Layout pensado para fonte grande e tablets
• 7 idiomas: English, Українська, Polski, Deutsch, Español, Français, Português (Brasil)

Privacidade. Sem análises, sem rastreadores, sem cadastro. O app funciona offline; a rede só é usada se você mesmo ativar o backup WebDAV.

BookVoices Pro é uma compra única, sem assinatura: pular silêncios e pausas, aumento de volume por hardware (+6 dB), equalizador de voz com correção de tom, marcador automático ao desconectar o Bluetooth e lista de personagens por livro. Todo o resto é grátis e sem anúncios.

BookVoices não vende nem transmite livros: ele toca os que você já tem.
```

**Novidades (1.16.0):**

```text
Sincronização mais inteligente entre dispositivos: o mesmo livro importado em dois aparelhos junta progresso, marcadores e tags em vez de duplicar. O WebDAV sincroniza um minuto após a pausa. Resumo do livro em Markdown, capa personalizada ou de um ZIP, abra arquivos .zip pelo gerenciador de arquivos, gerencie a fila. Retrocesso inteligente em todos os controles; restauração do Pro corrigida; arquivos órfãos são removidos; a importação continua ao sair da tela.
```

---

## Як вносити (коли вийде 11600)

1. Play Console → **Збільшення кількості користувачів → Сторінки додатків → Сторінка додатка за умовчанням →
   Редагувати**.
2. Для en-US і uk замінити повний опис текстом вище (короткий опис і назва не змінюються).
3. **Керувати перекладами → Додати власні переклади** → позначити польська (pl-PL), німецька (de-DE),
   іспанська (es-ES), французька (fr-FR), португальська (Бразилія) (pt-BR) → для кожної вставити назву
   `BookVoices`, короткий і повний опис. Скріншоти без перекладу Play бере з мови за умовчанням (en-US).
4. **Зберегти** → «Огляд публікації» → надіслати разом з випуском 11600, щоб не запускати дві перевірки.
5. «Що нового» для 11600 — блоки вище, у форматі `<pl-PL>…</pl-PL>` тощо в полі приміток до випуску.

## Перевірка

```python
# python docs/play-store/check-listing-lengths.py
```

Скрипт рахує довжини кожного блоку цього файла й падає, якщо короткий опис > 80, повний > 4000 чи
«Що нового» > 500 символів.
