# Сторінка Play під 1.18.0 — усі 7 мов

**Підготовлено 13 листопада 2026**, вносити разом із випуском 11800 (не раніше 19 листопада 2026).
На основі [`listing-1.17-all-languages.md`](listing-1.17-all-languages.md) (те, що зараз на сторінці). Зміни в кожній мові:
- рядок про перейменування й нумерацію розділів у розділі про відтворення;
- два рядки в розділі про статистику: щоденна автокопія в теку; серія днів, ціль на день, експорт історії в CSV;
- в абзаці про Pro додано повтор фрагмента A–B (6-та функція, як у пейволі);
- «Що нового» — примітки з [`release-notes-1.18.0.md`](release-notes-1.18.0.md).

Короткий опис і назва не змінюються. Скріншоти — ті самі [`screenshots/v1.17-*/`](screenshots/).

Довжини перевірено скриптом `check-listing-lengths.py` (без параметра він перевіряє цей файл).

---

## en-US

**Short:** Audiobook player for your own files: M4B, MP3, bookmarks, sleep timer. No ads.

```text
BookVoices is an audiobook player for your own collection. Add a folder of mp3, m4b, ogg or flac files and pick up exactly where you left off. No account, no ads, no subscription; your files stay on your phone.

LISTENING
• mp3, m4b, ogg, flac and opus; chapters from m4b, ID3 and CUE, and nested CD1/CD2 folders
• Rename chapters or number them after confirming
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
• Daily automatic backup to a folder you choose, keeping the last 7
• Listening streak, a daily goal and history export to CSV
• Export your catalog, bookmarks and a per-book summary as Markdown

COMFORT
• Night, Paper and true-black OLED themes
• Large-font and tablet friendly layouts
• 7 interface languages: English, Українська, Polski, Deutsch, Español, Français, Português (Brasil)

Privacy. No analytics, no trackers, no sign-up. The app works offline; the network is used only if you turn on WebDAV backup yourself.

BookVoices Pro is a one-time purchase, no subscription: silence and pause skipping, hardware Volume Boost (+6 dB), voice equalizer with pitch correction, auto-bookmark when headphones disconnect, per-book character lists and A–B repeat. Everything else is free, with no ads.

BookVoices doesn't sell or stream books: it plays the ones you already own.
```

**What's new (1.18.0):**

```text
New: a daily automatic backup to a folder you choose (keeps the last 7). Rename chapters or number them after confirming; new names sync over WebDAV. History shows your listening streak and a daily goal, and exports to CSV. Pro: A–B repeat in the player. "Report a problem" in Settings prepares an email with a diagnostic log; nothing is sent by itself. Back up straight to a file of your choice. Updated privacy policy.
```

---

## uk

**Short:** Плеєр аудіокниг для ваших файлів: M4B, MP3, закладки, таймер сну. Без реклами.

```text
BookVoices — плеєр аудіокниг для вашої власної колекції. Додайте теку з mp3, m4b, ogg чи flac — і слухайте з того місця, де зупинилися. Без акаунта, без реклами, без підписки; файли лишаються на вашому телефоні.

СЛУХАННЯ
• Формати mp3, m4b, ogg, flac, opus; глави з m4b, ID3 і CUE та вкладені теки CD1/CD2
• Перейменування розділів і «Пронумерувати» з підтвердженням
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
• Щоденна автокопія у вибрану теку, зберігає 7 останніх
• Серія днів прослуховування, ціль на день і експорт історії в CSV
• Експорт каталогу, закладок і конспекту окремої книги в Markdown

ЗРУЧНІСТЬ
• Теми «Ніч», «Папір» і справжній чорний OLED
• Великий шрифт і планшети: інтерфейс не ламається
• 7 мов інтерфейсу: українська, English, Polski, Deutsch, Español, Français, Português (Brasil)

Приватність. Жодної аналітики, трекерів і реєстрації. Застосунок працює без мережі; мережа потрібна лише якщо ви самі ввімкнете резервну копію WebDAV.

BookVoices Pro — одноразова покупка, без підписки: пропуск тиші та пауз, апаратний Volume Boost (+6 dB), голосовий еквалайзер із корекцією тону, автозакладка при від'єднанні гарнітури, список персонажів книги, повтор фрагмента A–B. Усе інше безкоштовне й без реклами.

BookVoices не продає і не стримить книги: він грає ті, що вже є у вас.
```

**Що нового (1.18.0):**

```text
Нове: щоденна автокопія у вибрану теку (зберігає 7 останніх). «Перейменувати розділ» і «Пронумерувати»; нові назви синхронізуються через WebDAV. В «Історії» — серія днів прослуховування, ціль на день і експорт у CSV. Pro: повтор фрагмента A–B у плеєрі. «Повідомити про проблему» в налаштуваннях готує лист із журналом діагностики — нічого не надсилається само. Бекап можна зберегти просто у файл. Оновлено політику конфіденційності.
```

---

## pl-PL

**Short:** Odtwarzacz audiobooków z Twoich plików: M4B, MP3, zakładki, timer. Bez reklam.

```text
BookVoices to odtwarzacz audiobooków dla Twojej własnej kolekcji. Dodaj folder z plikami mp3, m4b, ogg lub flac i słuchaj dokładnie od miejsca, w którym skończyłeś. Bez konta, bez reklam, bez subskrypcji; pliki zostają na Twoim telefonie.

SŁUCHANIE
• mp3, m4b, ogg, flac i opus; rozdziały z m4b, ID3 i CUE oraz zagnieżdżone foldery CD1/CD2
• Zmiana nazw rozdziałów i numerowanie po potwierdzeniu
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
• Codzienna automatyczna kopia do wybranego folderu (ostatnie 7)
• Seria dni słuchania, cel dzienny i eksport historii do CSV
• Eksport katalogu, zakładek i podsumowania książki do Markdown

WYGODA
• Motywy Noc, Papier i całkowicie czarny OLED
• Układ przyjazny dużej czcionce i tabletom
• 7 języków interfejsu: English, Українська, Polski, Deutsch, Español, Français, Português (Brasil)

Prywatność. Bez analityki, trackerów i rejestracji. Aplikacja działa offline; sieć jest używana tylko wtedy, gdy sam włączysz kopię zapasową WebDAV.

BookVoices Pro to jednorazowy zakup, bez subskrypcji: pomijanie ciszy i pauz, sprzętowe wzmocnienie głośności (+6 dB), korektor głosu z korekcją wysokości tonu, automatyczna zakładka po odłączeniu słuchawek, lista postaci dla każdej książki i powtarzanie fragmentu A–B. Cała reszta jest darmowa i bez reklam.

BookVoices nie sprzedaje ani nie streamuje książek: odtwarza te, które już masz.
```

**Co nowego (1.18.0):**

```text
Nowość: codzienna automatyczna kopia zapasowa do wybranego folderu (ostatnie 7). Zmiana nazw rozdziałów i „Ponumeruj”; nowe nazwy synchronizują się przez WebDAV. W historii: seria dni słuchania, cel dzienny i eksport do CSV. Pro: powtarzanie fragmentu A–B w odtwarzaczu. „Zgłoś problem” w ustawieniach przygotowuje e-mail z dziennikiem diagnostycznym — nic nie jest wysyłane samo. Kopię można zapisać do pliku. Zaktualizowana polityka prywatności.
```

---

## de-DE

**Short:** Hörbuch-Player für Ihre eigenen Dateien: M4B, MP3, Lesezeichen, Sleeptimer.

```text
BookVoices ist ein Hörbuch-Player für Ihre eigene Sammlung. Fügen Sie einen Ordner mit mp3-, m4b-, ogg- oder flac-Dateien hinzu und hören Sie genau dort weiter, wo Sie aufgehört haben. Kein Konto, keine Werbung, kein Abo; Ihre Dateien bleiben auf Ihrem Handy.

HÖREN
• mp3, m4b, ogg, flac und opus; Kapitel aus m4b, ID3 und CUE sowie verschachtelte CD1/CD2-Ordner
• Kapitel umbenennen oder nach einer Bestätigung nummerieren
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
• Übernimmt neue Bücher automatisch aus Ordnern Ihrer Wahl
• Markiert Bücher, deren Dateien verschwunden sind

STATISTIK UND DATEN
• Hörverlauf mit Wochen-, Monats- und Jahresdiagrammen
• Sicherung des Fortschritts als JSON oder auf Ihrem eigenen WebDAV-Server; Synchronisierung zwischen Handy und Tablet ohne doppelte Bücher
• Tägliche automatische Sicherung in einen Ordner Ihrer Wahl (die letzten 7)
• Hörserie, Tagesziel und Export des Verlaufs als CSV
• Export von Katalog, Lesezeichen und einer Buchzusammenfassung als Markdown

KOMFORT
• Themes Nacht, Papier und echtes Schwarz für OLED
• Für große Schrift und Tablets geeignete Layouts
• 7 Sprachen: English, Українська, Polski, Deutsch, Español, Français, Português (Brasil)

Datenschutz. Keine Analyse, keine Tracker, keine Anmeldung. Die App funktioniert offline; das Netzwerk wird nur genutzt, wenn Sie die WebDAV-Sicherung selbst einschalten.

BookVoices Pro ist ein einmaliger Kauf, kein Abo: Überspringen von Stille und Pausen, Hardware-Lautstärkeverstärkung (+6 dB), Sprach-Equalizer mit Tonhöhenkorrektur, automatisches Lesezeichen beim Trennen der Kopfhörer, Figurenlisten pro Buch und A–B-Wiederholung. Alles andere ist kostenlos und ohne Werbung.

BookVoices verkauft oder streamt keine Bücher: Es spielt die, die Sie bereits besitzen.
```

**Neuigkeiten (1.18.0):**

```text
Neu: tägliche automatische Sicherung in einen Ordner Ihrer Wahl (die letzten 7). Kapitel umbenennen oder „Nummerieren“; neue Namen werden per WebDAV synchronisiert. Im Verlauf: Hörserie, Tagesziel und CSV-Export. Pro: A–B-Wiederholung im Player. „Problem melden“ in den Einstellungen bereitet eine E-Mail mit Diagnoseprotokoll vor – nichts wird automatisch gesendet. Sicherung direkt in eine Datei speichern. Datenschutzerklärung aktualisiert.
```

---

## es-ES

**Short:** Reproductor de audiolibros de tus archivos: M4B, MP3, marcadores. Sin anuncios.

```text
BookVoices es un reproductor de audiolibros para tu propia colección. Añade una carpeta con archivos mp3, m4b, ogg o flac y continúa justo donde lo dejaste. Sin cuenta, sin anuncios, sin suscripción; tus archivos se quedan en tu teléfono.

ESCUCHAR
• mp3, m4b, ogg, flac y opus; capítulos de m4b, ID3 y CUE, y carpetas anidadas CD1/CD2
• Cambia el nombre de los capítulos o numéralos tras confirmar
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
• Copia de seguridad diaria automática en la carpeta que elijas (las 7 últimas)
• Racha de días de escucha, meta diaria y exportación del historial a CSV
• Exporta tu catálogo, marcadores y un resumen de cada libro en Markdown

COMODIDAD
• Temas Noche, Papel y negro puro OLED
• Diseño cómodo con letra grande y en tabletas
• 7 idiomas: English, Українська, Polski, Deutsch, Español, Français, Português (Brasil)

Privacidad. Sin analíticas, sin rastreadores, sin registro. La app funciona sin conexión; la red solo se usa si activas tú mismo la copia de seguridad WebDAV.

BookVoices Pro es una compra única, sin suscripción: salto de silencios y pausas, amplificación de volumen por hardware (+6 dB), ecualizador de voz con corrección de tono, marcador automático al desconectar los auriculares, lista de personajes por libro y repetición A–B. Todo lo demás es gratis y sin anuncios.

BookVoices no vende ni transmite libros: reproduce los que ya tienes.
```

**Novedades (1.18.0):**

```text
Novedades: copia de seguridad diaria automática en la carpeta que elijas (guarda las 7 últimas). Cambia el nombre de los capítulos o «Numerar»; los nombres se sincronizan por WebDAV. En el historial: racha de días de escucha, meta diaria y exportación a CSV. Pro: repetición A–B en el reproductor. «Informar de un problema» en Ajustes prepara un correo con el registro de diagnóstico; no se envía nada solo. Guarda la copia en un archivo. Política de privacidad actualizada.
```

---

## fr-FR

**Short:** Lecteur de livres audio pour vos fichiers : M4B, MP3, signets. Sans publicité.

```text
BookVoices est un lecteur de livres audio pour votre propre collection. Ajoutez un dossier de fichiers mp3, m4b, ogg ou flac et reprenez exactement là où vous vous êtes arrêté. Sans compte, sans publicité, sans abonnement ; vos fichiers restent sur votre téléphone.

ÉCOUTE
• mp3, m4b, ogg, flac et opus ; chapitres depuis m4b, ID3 et CUE, et dossiers imbriqués CD1/CD2
• Renommez les chapitres ou numérotez-les après confirmation
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
• Sauvegarde automatique quotidienne dans le dossier de votre choix (les 7 dernières)
• Série de jours d'écoute, objectif quotidien et export de l'historique en CSV
• Export du catalogue, des signets et d'un résumé de livre en Markdown

CONFORT
• Thèmes Nuit, Papier et noir pur OLED
• Affichage adapté aux grandes polices et aux tablettes
• 7 langues : English, Українська, Polski, Deutsch, Español, Français, Português (Brasil)

Confidentialité. Aucune analyse, aucun traceur, aucune inscription. L'app fonctionne hors ligne ; le réseau n'est utilisé que si vous activez vous-même la sauvegarde WebDAV.

BookVoices Pro est un achat unique, sans abonnement : saut des silences et des pauses, amplification matérielle du volume (+6 dB), égaliseur vocal avec correction de hauteur, signet automatique au débranchement du casque, liste des personnages par livre et répétition A–B. Tout le reste est gratuit et sans publicité.

BookVoices ne vend ni ne diffuse de livres : il lit ceux que vous possédez déjà.
```

**Nouveautés (1.18.0) :**

```text
Nouveau : sauvegarde automatique quotidienne dans le dossier de votre choix (les 7 dernières). Renommez les chapitres ou « Numéroter » ; les noms se synchronisent via WebDAV. Dans l'historique : série de jours d'écoute, objectif quotidien et export CSV. Pro : répétition A–B dans le lecteur. « Signaler un problème » dans les réglages prépare un e-mail avec le journal de diagnostic ; rien n'est envoyé tout seul. Sauvegarde dans un fichier. Politique de confidentialité mise à jour.
```

---

## pt-BR

**Short:** Player de audiolivros dos seus arquivos: M4B, MP3, marcadores. Sem anúncios.

```text
BookVoices é um player de audiolivros para a sua própria coleção. Adicione uma pasta com arquivos mp3, m4b, ogg ou flac e continue exatamente de onde parou. Sem conta, sem anúncios, sem assinatura; seus arquivos ficam no seu celular.

OUVIR
• mp3, m4b, ogg, flac e opus; capítulos de m4b, ID3 e CUE, e pastas aninhadas CD1/CD2
• Renomeie capítulos ou numere-os após confirmar
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
• Backup automático diário na pasta que você escolher (os 7 últimos)
• Sequência de dias ouvindo, meta diária e exportação do histórico em CSV
• Exporte catálogo, marcadores e um resumo de cada livro em Markdown

CONFORTO
• Temas Noite, Papel e preto puro OLED
• Layout pensado para fonte grande e tablets
• 7 idiomas: English, Українська, Polski, Deutsch, Español, Français, Português (Brasil)

Privacidade. Sem análises, sem rastreadores, sem cadastro. O app funciona offline; a rede só é usada se você mesmo ativar o backup WebDAV.

BookVoices Pro é uma compra única, sem assinatura: pular silêncios e pausas, aumento de volume por hardware (+6 dB), equalizador de voz com correção de tom, marcador automático ao desconectar os fones, lista de personagens por livro e repetição A–B. Todo o resto é grátis e sem anúncios.

BookVoices não vende nem transmite livros: ele toca os que você já tem.
```

**Novidades (1.18.0):**

```text
Novo: backup automático diário na pasta que você escolher (mantém os 7 últimos). Renomeie capítulos ou use "Numerar"; os nomes sincronizam pelo WebDAV. No histórico: sequência de dias ouvindo, meta diária e exportação em CSV. Pro: repetição A–B no player. "Relatar um problema" nas configurações prepara um e-mail com o log de diagnóstico; nada é enviado sozinho. Salve o backup em um arquivo. Política de privacidade atualizada.
```

---

## Як вносити (разом із випуском 11800)

1. Play Console → **Збільшення кількості користувачів → Сторінки додатків → Сторінка додатка за умовчанням → Редагувати**.
2. Для en-US і uk замінити повний опис текстом вище; **Керувати перекладами** → для pl-PL, de-DE, es-ES, fr-FR, pt-BR — так само.
3. **Далі → Огляд → Зберегти** («Зберегти як чернетку» в огляд публікації зміни не виносить).
4. «Огляд публікації» → надіслати разом із випуском 11800, щоб була одна перевірка.

## Перевірка

```python
# python -X utf8 docs/play-store/check-listing-lengths.py
```
