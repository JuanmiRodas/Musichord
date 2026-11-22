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
