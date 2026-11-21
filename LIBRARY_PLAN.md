# Biblioteca ordenada en pCloud: plan acordado (21 nov 2026)

Objetivo: toda la música que te gusta acaba en `pCloud Drive/pCloud Backup/MacBookAirdeJuanmi/Music/Música`, ordenada, en **MP3 320 auténtico** y con metadatos completos. BitChord y la app del Mac trabajan sobre esa misma carpeta.

## Reglas

- **Formato:** solo MP3 320, hecho desde FLAC/CD o comprado como 320 auténtico.
  - Nunca AAC, m4a, opus, webm ni wma.
  - Nunca un 256 pasado a 320.
- **Misma versión:** la sustituta tiene que coincidir en huella acústica, duración (±2 s) e ISRC cuando lo haya. Se avisa de «TV size», remaster y directo.
- **Falsos 320** (un «320» sin nada por encima de 16,5 kHz): se usan mientras tanto y van a la to-do con prioridad.
- **To-do de sustitución:** lo que no se encuentre en las fuentes. Agrupada por discografía, álbum, colección o canción. Incluye lo que solo está en el móvil (`.bitchord-audio/todo-phone.json`).
- **Fuentes:**
  - tu pCloud y tus discografías;
  - compras: Bandcamp, 7digital, Qobuz, mora, OTOTOY;
  - CD;
  - archive.org, solo lo subido con permiso.

## Estructura

- Todo al mismo nivel dentro de `Música/`: artistas y colecciones.
- **Artista con carpeta propia** a partir de **5 canciones**. Con menos, sus canciones viven en la colección (serie o juego).
- **Colecciones multiartista** (Sailor Moon, Card Captor Sakura, Tales of): por obra y luego por álbum, por ejemplo `Tales of/Tales of Vesperia/2008 - Álbum`. Las canciones sueltas van a `Sueltas`.
- **Álbum:** `Año - Álbum (日本語)`.
- **Archivo:** `01 - Título (日本語) - Artista (日本語) - Álbum (日本語) - Año.mp3`. El disco 2 se escribe `2-01`.
- **Instancias:** un solo archivo por grabación. Las colecciones son listas `.m3u8` más etiquetas:
  - `GROUPING` = obra;
  - `SERIES`, `WORK_TYPE` y `USAGE`, por ejemplo «Opening – Tales of Vesperia (versión occidental)».
- **Versiones regionales:** una sola obra con nota de región.

## BitChord (móvil)

- Hecho en v1.9.288: `LibraryQuality`.
  - El espejo y las copias a pCloud solo suben MP3 o sin pérdida, y nada que ya esté en la biblioteca.
  - La limpieza ya no convierte a AAC.
  - Por la noche, con WiFi, las descargas que no son MP3 320 se sustituyen sin avisar por la copia de la biblioteca: huella acústica comprobada y la antigua borrada solo después de registrar la nueva.
  - La to-do del móvil se escribe en `pCloud/.bitchord-audio/todo-phone.json`.

## App del Mac (`macLibrary/`, Compose Desktop; workflow `mac.yml`, pre-release `mac-library-1.0.N`)

- **Fase 1 (hecha):**
  - lee la biblioteca y las carpetas por integrar con ffprobe, que va incluido en la app;
  - resumen de calidad, etiquetas y portadas;
  - to-do agrupada y exportable a CSV, con la parte del móvil;
  - búsqueda de falsos 320;
  - conversión de FLAC a MP3 320, con el original guardado en `_Archivo BitChord`;
  - vista previa de los nombres.
- **Fase 2:**
  - aplicar nombres y carpetas, con registro para deshacer;
  - regla de 5 canciones por artista;
  - integrar `Downloads/Discografías`.
- **Fase 3:**
  - colecciones y series (AnimeThemes, AniDB, VGMdb, MusicBrainz, el ADN de BitChord);
  - etiquetas de obra y uso;
  - listas `.m3u8`;
  - huella acústica (Chromaprint) para emparejar versiones.
- **Fase 4:** reproductor de escritorio de BitChord integrado, partiendo del `desktopApp` del fork de ayhamo, para sustituir a Apple Music en el Mac.


## Cambio del 21 nov (noche): biblioteca nueva desde fuentes de solo lectura

- **Biblioteca nueva:** `~/pCloud Drive/Biblioteca`, en la nube. Hacen falta ~123 GB y el Mac solo tiene 43 GB libres.
- **Fuentes, que nunca se modifican:** `~/Desktop/Música`, `~/Music/Música(1)`, `~/Downloads/Discografías` y `~/pCloud Drive/OnlineOnly/Yuki Kajiura`.
- **Reglas de la carpeta de Kajiura:**
  - entran FICTION, The Works, todos los directos y conciertos, todos los singles y sus grupos (FictionJunction, YUUKA, ASUKA, Kalafina, See-Saw, Saeko Chiba);
  - de las bandas sonoras, solo las pistas cantadas.
- **Calidad:** de cada canción se copia la mejor versión. Si solo hay MP3 por debajo de 320 o falsos 320, se copian mientras tanto y van a la to-do. No se copian karaoke, «off vocal» ni instrumentales.
- **Colecciones:** carpetas de muchos artistas cuyo nombre aparece en las canciones (anime, juegos, series). Cada canción va al álbum de la colección que más canciones reúne.
- **Alias:** van en `Biblioteca/.bitchord-library/collections.json`, por ejemplo «美少女戦士セーラームーン» → «Sailor Moon».
- **Artistas:** una carpeta por persona, con el nombre «Romaji (日本語)».
- **Portadas alternativas:** base de portadas sin duplicados, una por álbum, y propuestas para aprobar en la to-do.
- **Liberar espacio:** las carpetas de las fuentes ya cubiertas se archivan en `OnlineOnly/Antigua biblioteca de música`, se comprueban y se borran del Mac.
- **Pendiente:** BitChord, con la carpeta de discografía como recurso de la página de artista (álbumes por año fusionados con YouTube Music sin repetir, y vídeos de conciertos).

## 22 nov: patrones corregidos tras la primera construcción (1.0.11)
- **Archivos sin etiquetas**: se confundían entre sí por duración (Miguel Bosé en «Detective Conan», Pokémon en «Kalafina AI», el «artista» `chiaki x kalafina anna.mp3 ()`). Ahora una grabación es título + persona + duración, y lo que falta se lee del nombre del archivo y de las carpetas (`Words.fromName`).
- **Etiquetas que no nombran nada** («Anime», «Varios», «Música normal o anime», «Desconocido», webs de descarga, nombres de archivo, números): se ignoran (`Words.noArtist/noAlbum`). Un «artista» que solo aparece en un álbum de su mismo nombre («Tales of Symphonia», «Ar Tonelico») es la serie, no una persona. «Otros artistas» nunca se escribe como etiqueta.
- **Texto mal decodificado** (japonés/chino leído como Latin‑1: `°®ÄÚÀï²Ë`, `¥¹¥ì¥¤¥ä©`¥º`): se repara (`Words.fix`), sin tocar «Fantôme» ni «Malmö».
- **Una persona, un nombre**: mayúsculas, acentos, orden, «[nZk]», romanizaciones (Ohnuki/Onuki), Ke$ha/Kesha, NFC/NFD; latino ↔ japonés solo cuando cada uno es la mejor pareja del otro (no más «Ayumi Hamasaki (宇多田ヒカル)»). Se muestra la grafía con mayúsculas y acentos («Naomi Tamura», «Miguel Bosé»).
- **Álbum de un artista ≠ colección**: una carpeta cuyos temas comparten artista del álbum no es colección (2V‑ALK, RƎ⫽MEMBER → SawanoHiroyuki[nZk]); dentro de la carpeta de un artista no hay colecciones («Zara Larsson/Varios 2016»); una carpeta con nombre de persona tampoco.
- **Colecciones**: una por serie aunque se llame distinto (Pokemon/Pokémon/Pocket Monsters/Pikachu; alias en `collections.json` + integrados); familias por prefijo («Tales of …» dentro de «Tales of»; NARUTO = Naruto); los géneros («Español», «Classical», «Pop») no son colecciones; un álbum que nombra una colección va a ella salvo que sea un álbum propio del artista (≥4 temas suyos) — así los sencillos de Pokémon de Naomi Tamura van a Pokémon; un tema sin artista con álbum (un juego, una serie) forma colección con ese álbum; sin álbum, su título o archivo puede nombrar la serie («a2 jap One Piece end4»).
- **Un año por carpeta de álbum** (el más repetido; empate, el último): «Lost» queda en una carpeta. «Sueltas» sin año.
- **Yuki Kajiura**: lo suyo encontrado en otras fuentes pasa por sus mismas reglas (solo vocales de OST, directos a «Directos»…), lo de sus grupos va a cada grupo (FictionJunction KAORI → FictionJunction), y lo que otros cantan en recopilatorios suyos va al cantante (ALI PROJECT, Chiaki Ishikawa).
- **Ordenar lo ya copiado** (`Engine.build`): `built.json` dice de qué fuente viene cada archivo; con el plan nuevo se mueven y re-etiquetan (sin volver a copiar), se reemplazan si hay mejor copia, se quitan los que sobran (solo hechos por la app), se arreglan carpetas que solo difieren en mayúsculas y se borran carpetas vacías. Lo que la app no reconoce se deja y se lista.
- **Para Claude**: `live.json` (estado cada pocos segundos), `log.txt` (una línea por archivo), `inbox/*.json` → `outbox/*.txt` (`status`, `stop`, `scan`, `build`, `scan+build`, `update` con `then`, `cli` con `args`). La app se actualiza sola (`Updater`, releases `mac-library-*`).
- **ffmpeg colgado**: las órdenes externas ya no pueden bloquear (salida a archivo + límite de tiempo); por eso se quedaba parada en 3751.
- Pendiente: identificar por huella (AcoustID) lo que queda en «Sin identificar» (sobre todo «Música de los vídeos OPS»).
