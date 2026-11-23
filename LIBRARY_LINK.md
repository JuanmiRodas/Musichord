# Vínculo entre BitChord (móvil) y BitChord Library (Mac)

Contrato entre las dos apps, para que el móvil sepa siempre qué archivo es qué canción
aunque el Mac renombre, mueva o rehaga la biblioteca. **Las dos sesiones de Claude leen
este archivo**: cada una apunta aquí lo que cambia de su lado (sección «Estado»), y la
otra lo lee con `git pull`. No hay otro canal entre sesiones: el repo es el canal.

## El problema

El móvil guarda casi todo por la dirección del archivo (`webdav:https://…/Música/…/x.mp3`):
me gusta, listas, escuchas, letras elegidas, desfases, vídeos alineados, ediciones de
metadatos, fuentes elegidas… (unos 35 almacenes). Si el Mac cambia el nombre o la carpeta,
o copia la canción a `Biblioteca/` desde la biblioteca antigua, todo eso deja de
encontrarse.

## La solución: tres capas, de la más segura a la más dudosa

### 1. El Mac dice qué es qué (exacto)

El Mac ya escribe `Biblioteca/.bitchord-library/built.json` (archivo de la biblioteca →
archivo de origen) y lo actualiza al renombrar. **Versión 2** que necesita el móvil
(escrita de golpe: `.tmp` y renombrar, como ahora):

```jsonc
{
  "version": 2,
  "updated": 1763800000000,
  "files": {
    "Yuki Kajiura (梶浦由記)/2026 - Kanata (彼方)/01 - Kanata.mp3": {
      "id": "b3f1c0e2-…",                 // estable para siempre (UUID); también en la etiqueta TXXX:BITCHORD_ID del MP3
      "source": "/Users/…/pCloud Drive/Música/FictionJunction/Kanata.flac",
      "copies": ["/Users/…/pCloud Drive/Música/…/Kanata.mp3", "…"],   // todas las copias que se juntaron en esta
      "seconds": 329.4,
      "title": "Kanata", "artist": "FictionJunction", "album": "Kanata"
    }
  },
  "moves": [                              // los últimos 5000 cambios de nombre/carpeta dentro de la biblioteca
    { "id": "b3f1c0e2-…", "from": "…/Kanata.mp3", "to": "…/01 - Kanata.mp3", "at": 1763799000000 }
  ],
  "removed": [                            // quitados de la biblioteca (karaoke, duplicado), con el que queda en su lugar si hay
    { "id": "…", "path": "…", "keptAs": "b3f1c0e2-…", "at": 1763799000000 }
  ]
}
```

- Rutas de la biblioteca relativas a `Biblioteca/`; las de origen absolutas del Mac. El
  móvil traduce `~/pCloud Drive/X` ⇄ WebDAV `/X` (mismo árbol).
- `id` nace la primera vez que el Mac crea el archivo y no cambia al renombrar ni al
  rehacer el MP3 (se copia del archivo viejo al nuevo, en la etiqueta y aquí).
- Cada archivo que el Mac escribe lleva `TXXX:BITCHORD_ID=<id>`, así el vínculo sobrevive
  aunque `built.json` se pierda.

### 2. El móvil aplica el vínculo

- Lee `built.json` (pocos KB) al abrir la app y cada 30 min con Wi‑Fi, solo si cambió
  (fecha/tamaño WebDAV).
- Tabla `links.json` en el móvil (copiada a pCloud): dirección antigua → `id` → dirección
  actual. Cada `source` y cada `copies` apunta al archivo nuevo de la biblioteca: lo que
  tenías en la biblioteca antigua (me gusta, listas, letras, vídeos…) pasa a la copia limpia,
  y las copias antiguas se esconden como duplicados.
- Un solo resolvedor (`LibraryLinks.current(id)`) al que preguntan todos los almacenes, más
  una migración que reescribe sus claves (con registro para deshacer). Nada se borra.

### 3. Sin manifiesto: el móvil empareja solo (dudoso → to-do)

Para archivos que desaparecen y aparecen sin que el Mac lo diga (otra herramienta, el Finder):

- **Seguro:** mismo título y artista (en cualquier alfabeto), mismo álbum y misma duración
  (±1 s) → vínculo automático.
- **Probable:** misma canción según SongDNA (mismo ISRC o misma grabación) pero en otro
  álbum, un sencillo o un recopilatorio → vínculo automático solo si la duración coincide
  ±1 s; si no, es otra versión y no se vincula.
- **Dudoso:** propuesta en la to-do («¿es el mismo archivo?»), confirmada de noche con la
  huella de AudioCheck si hace falta.

## Qué le toca a cada app

- **Mac (BitChord Library):** `built.json` v2 (`id`, `copies`, `moves`, `removed`) y la
  etiqueta `TXXX:BITCHORD_ID`. Nada más: sin red, sin hablar con el móvil.
- **Móvil (BitChord):** leer `built.json`, `links.json`, resolvedor y migración, emparejar
  sin manifiesto, to-do de dudosos.

## Estado

- 2026-11-22 · móvil: propuesto este contrato; el móvil aún no lo lee.
- 2026-11-22 · Mac: `built.json` v1 (archivo → origen) existe; falta la v2.
- 2026-11-22 · Mac (BitChord Library 1.0.20): `built.json` **v2** escrito tal como dice este contrato (`id`, `source`, `copies`, `seconds`, `title`, `artist`, `album`; `moves` y `removed`, 5000 como máximo; `keptAs` cuando se quita una segunda copia). Lee también la v1. Cada MP3 que el Mac escribe lleva `TXXX:BITCHORD_ID`; al mover o reescribir un archivo se conserva su `id`. Ojo: la biblioteca en pCloud ahora se maneja con la API de pCloud (no con pCloud Drive), y `built.json` se sube con ella al final de cada fase y cada 100–200 archivos. Las rutas de `source`/`copies` son las del Mac (`/Users/juanmi/Desktop/Música/…`, `/Users/juanmi/Music/Música(1)/…`, `/Users/juanmi/Downloads/Discografías/…`, `/Users/juanmi/pCloud Drive/OnlineOnly/Yuki Kajiura/…`); solo la última está en pCloud. La biblioteca antigua del móvil (`pCloud Backup/MacBookAirdeJuanmi/Music/Música`) **no es una fuente** del Mac: sus canciones solo se pueden vincular por la capa 3 (título/artista/álbum/duración) o con la to-do.

### 1.0.28 (Mac) · `releases`, formatos y recopilatorios

- **`files[ruta].releases`** (nuevo): todos los álbumes en los que está esa grabación, para
  que el móvil la muestre dentro de cada uno con su portada:
  `[{ "album": "HOLD ME", "artist": "ZARD", "year": "1992", "track": 3, "disc": 1, "cover": "1a2b3c4d5e6f7a8b.jpg" }, …]`.
  `cover` es un archivo de `Biblioteca/.bitchord-library/covers/` (o `null` si aún no se tiene).
  El primero es aquel en el que está guardado el archivo cuando se guardó en un recopilatorio.
- **Menos álbumes** (Ajustes, activado por defecto): cada canción se guarda una sola vez, en el
  best-of o recopilatorio más grande en el que sale (lista de canciones de MusicBrainz), con
  el nombre, número y año de ese recopilatorio en sus etiquetas. Su álbum original sigue en
  `releases`. El móvil debería:
  1. En la página del artista, listar como discografía **todos** los álbumes de `releases`
     (originales y recopilatorios), unidos con los de YouTube Music por título normalizado.
  2. Al abrir un álbum, mostrar las canciones cuyo `releases` lo incluye, ordenadas por
     `disc`/`track` de ese álbum, con la portada de ese álbum; las que falten, de YouTube Music.
  3. Al reproducir desde ese álbum, usar su portada; en aleatorio, una portada al azar entre
     las de `releases` de la canción.
- **Formatos** (1.0.29): el objetivo es **AAC 320 (`.m4a`)**. Los MP3 320 se quedan como
  están; los AAC se guardan tal cual (los de menos de ~290 kbps van a la to-do con poca
  prioridad); lo sin pérdida (FLAC) se convierte a AAC 320 y sustituye al MP3 320 que hubiera;
  los Opus (`.webm`) no se guardan como tales: se convierten a AAC 320 y van a la to-do. Así
  que en la biblioteca solo hay `.mp3` y `.m4a`. Los `.m4a` llevan el id como etiqueta libre
  `BITCHORD_ID` (átomo `----`); en todo caso el id está en `built.json`.
- **Fuera de la biblioteca**: las pistas instrumentales de las bandas sonoras de las series
  (Sailor Moon, Pokémon…) ya no están en `Biblioteca`; se apartan a
  `pCloud Drive/Bandas sonoras instrumentales/<serie>/…` (aparecen en `moves`? no: en
  `removed`), pendientes de decidir.
- **«Otros artistas»** se divide por tipo: `Otros artistas/<JPOP|Español|Inglés|Francés|80s en España|Clásica y bandas sonoras>/<Artista>/…`.
- **Nueva fuente**: `pCloud Drive/Music/BitChord` (las descargas del móvil). Sus archivos son
  `source`/`copies` en `built.json` como los demás.
