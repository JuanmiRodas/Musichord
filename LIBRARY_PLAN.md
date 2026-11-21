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
