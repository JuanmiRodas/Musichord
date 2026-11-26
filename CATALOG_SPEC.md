# BitChord Unified Catalog & YouTube Music Merge Specification (`CATALOG_SPEC.md`)

Contrato paralelo a [`LIBRARY_LINK.md`](LIBRARY_LINK.md) para fusionar las páginas de artistas, álbumes y series de anime de tu biblioteca personal (`pCloud Drive/Biblioteca`) con el catálogo de **YouTube Music**, sin duplicados y eligiendo siempre la copia de mayor calidad.

> **Estado actual:** Datos y esquema generados en `Biblioteca/.bitchord-library/catalog/` (espejo local en `~/Library/Application Support/BitChord Library/catalog/`). Todavía **no** implementado en Android (listo para consumir en la siguiente fase).

---

## 1. Arquitectura modular de archivos

Todo vive en `Biblioteca/.bitchord-library/catalog/` para que BitChord (móvil) cargue primero el índice ligero (`manifest.json` + `name-index.json`) y descargue bajo demanda solo el archivo del artista o anime que el usuario abre:

```text
Biblioteca/.bitchord-library/catalog/
├── manifest.json             # Versión de esquema, fecha, estadísticas globales y mapa de rutas
├── name-index.json           # Índice invertido O(1): alias / soundKey / autoRomaji / UC... / MPREb_... / videoId → entidad
├── works-versions.json       # Grafo de versiones por obra (Original JP ⇄ TV Size ⇄ Live ⇄ Acoustic ⇄ Remix ⇄ Remaster ⇄ ES/EN)
├── yuki-kajiura.json         # Hub especial del universo Yuki Kajiura (canta-per-me.net + YTM + Biblioteca)
├── artists-index.json        # Índice ligero de los ~196 artistas con carpeta propia + proyectos relacionados
├── artists/<slug>.json       # Página fusionada de cada artista (YTM + Biblioteca, álbumes híbridos, completitud, aportes a anime)
├── anime-index.json          # Índice ligero de las 59 series/franquicias de anime y videojuegos en tu biblioteca
└── anime/<slug>.json         # Página completa por serie (Openings, Endings, Películas, Otras, Álbumes, OST, Personajes/Seiyuu, ES/EN/CA)
```

---

## 2. Sistema de nombres multi-referencia (`names`) y predicción `autoRomaji`

Cada artista, álbum, canción, obra y serie lleva un bloque estándar `names` diseñado para eliminar cualquier fricción entre **Kanji / Kana**, **títulos oficiales en inglés**, **títulos en español**, **múltiples variantes de romanización** y el **romaji automático de BitChord** (`JapaneseReading.kt`):

```json
"names": {
  "canonical": "Spurt! (スパート！)",
  "jp": "スパート！",
  "en": "Spurt!",
  "es": "Spurt!",
  "romaji": ["Spurt!", "Supāto!", "Supato!", "Supaato!"],
  "autoRomaji": "supaato!",
  "soundKeys": ["spurt", "supato"],
  "aliases": ["spurtスパート", "spurt", "スパート", "supaato", "supato"]
}
```

- **`autoRomaji`**: predicción guardada por defecto de cómo un conversor automático Hepburn (Kuromoji / `pykakasi`) lee el texto japonés (`スパート！` → `supaato!`).
- **`soundKeys`**: réplica exacta de `JapaneseReading.soundKey(...)` (`tsu→tu`, `shi→si`, `chi→ti`, `sha→sya`, `ou/oo→o`, `uu→u`, `aa→a`, `wo→o`, `ha/wa→a`, `he→e`) precalculada para **todas** las variantes (`en`, `romaji`, `autoRomaji`), de modo que Android empareje en **O(1)** incluso antes de que cargue el diccionario Kuromoji.
- **`aliases`**: claves normalizadas (`OwnFileMatcher.key`) en todos los alfabetos.

---

## 3. Fusión de páginas de artistas y álbumes híbridos (`artists/<slug>.json`)

1. **Vínculo 1:1 con YouTube Music:** cada artista guarda `ytm.browseId` (`UC...`), `ytm.altBrowseIds` (canales Topic/secundarios para evitar duplicados), `nameJa`, `nameEn`, y cada lanzamiento guarda `ytmBrowseId` (`MPREb_...`) y `tracks[].ytmVideoId`.
2. **Disponibilidad por lanzamiento (`availability`):**
   - `"both"`: existe tanto en tu biblioteca como en YouTube Music → se muestra **una sola tarjeta** (sin duplicar entre japonés/romaji/remaster).
   - `"library_only"`: álbumes, singles, directos, doujin o ediciones exclusivas que **no están en YouTube Music** (ej. *Tatsuro Yamashita*, *Akiko Shikata*, conciertos de *Yuki Kajiura*, cajas de *Sailor Moon*) → se intercalan cronológicamente con badge `☁ pCloud`.
   - `"ytm_only"`: lanzamientos disponibles en YouTube Music que todavía no tienes descargados.
3. **Selección automática de mayor calidad (`preferredSource`) e indicador de completitud (`completion`):**
   - Cada pista de un álbum híbrido compara tu archivo local (`libraryPath` + `quality.lossless` / `bitrate`) contra YouTube Music (`ytmVideoId`, ~160 kbps Opus):
     - `"library"`: copia local lossless o ≥ 256 kbps (MP3/AAC 320) → **reproduce tu archivo local automáticamente**.
     - `"library_low_quality"`: copia local < 256 kbps → deja decidir a `AppSettings.sourcePreference` (o sugiere mejora vía `wanted-320.json`).
     - `"ytm"`: pista que te falta en ese álbum → reproduce en streaming desde YouTube Music sin romper el álbum.
   - `appearances[]` enlaza cada grabación física a **todos** los álbumes/singles/recopilatorios donde aparece (alineado con los alias de macOS y `built.json` v2 `releases[]`).
4. **Enlaces cruzados (`relatedProjects` y `animeContributions`):**
   - Proyectos hermanos enlazados bidireccionalmente (ej. `L'Arc~en~Ciel` ⇄ `HYDE` / `TETSUYA` / `Ken` / `acid android` / `P'UNK~EN~CIEL`; `Utada Hikaru` ⇄ `Cubic U`; `La Oreja de Van Gogh` ⇄ `Amaia Montero`).
   - Cada artista muestra sus contribuciones a series de anime (`animeContributions[]`) enlazadas a `anime/<slug>.json`.

---

## 4. Hub especial de Yuki Kajiura (`yuki-kajiura.json`)

Construido cruzando **canta-per-me.net** (`/discography/per-project/`, `/discography/alphabetical-listing/`, `/yukis-vocalists/`), YouTube Music y tu biblioteca, separando en secciones claras:

1. **Álbumes en solitario y Yuki Kajiura LIVE** (`Fiction`, `Fiction II`, `30th Anniversary`, directos Vol. 1–22).
2. **Grupos y proyectos propios:** `Kalafina`, `FictionJunction` (con sub-unidades `KAORI`, `KEIKO`, `WAKANA`, `YURIKO KAIDA`, `ASUKA`), `FictionJunction YUUKA`, `See-Saw`, `Dream Port` (con Revo).
3. **Artistas producidos / vinculados:** `Saeko Chiba` (*melody*, *everything*), `Yuuka Nanri`, `Kaori Oda`, `Chiaki Ishikawa`.
4. **Colaboraciones vocales producidas por Kajiura:** *Aimer* (trilogía *Fate Heaven's Feel*, *Kimetsu*), *LiSA* (*from the edge*, *Homura*, *Akeboshi*, *Shirogane*), *Yui Makino*, *Haruna Luna*, *Eir Aoi*, *ASCA*, *JUNNA*.
5. **Bandas sonoras por franquicia (54 proyectos):** cada una enlazada por `animeId` a su página de anime (`.hack`, *Noir*, *Tsubasa Chronicle*, *Kara no Kyoukai*, *Pandora Hearts*, *Madoka Magica*, *Fate*, *Sword Art Online*, *Kimetsu no Yaiba*, *My-HiME*, *Gundam SEED*, *MADLAX*, *El Cazador de la Bruja*, *Xenosaga*, etc.).

---

## 5. Páginas por serie de anime (`anime/<slug>.json`)

Cada una de las 59 series en tu biblioteca tiene su propia página virtual con estanterías paralelas a tu estructura de carpetas:

- **`openings`** y **`endings`**: ordenados cronológicamente por orden de aparición (incluyendo secuelas/OVAs), marcando qué temas posees físicamente, cuáles llegan por alias desde su artista (ej. *Ayumi Hamasaki* → *Inuyasha*, *Maaya Sakamoto* → *Tsubasa Chronicle*) y cuáles faltan para reproducir desde YouTube Music.
- **`movies`**: temas principales de películas ordenados por año (`YYYY - NN - Título - Artista - Película`).
- **`insertAndOther`**: insert songs y lanzamientos varios (`Otras`).
- **`albumsAndSingles`**: discos oficiales y recopilatorios de la serie.
- **`soundtracks`**: bandas sonoras enlazadas a la página de sus compositores (`composers[].artistSlug`).
- **`characterSongs`**: canciones de personajes con campos separados `character` y `seiyuu` (CV).
- **`localizedVersions`**: canciones en **español (`es`)**, **catalán (`ca`)** e **inglés (`en`)**, enlazadas mediante `originalWorkId` a su tema original japonés en `works-versions.json`.

---

## 6. Grafo de versiones por obra (`works-versions.json`) e Índice O(1) (`name-index.json`)

- **`works-versions.json`**: agrupa bajo un mismo `workId` todas las versiones de una misma obra musical (`original`, `tv_size`, `live`, `acoustic`, `remix`, `remaster`, `instrumental`, `localized_es`, `localized_en`, `localized_ca`) para permitir un selector «Cambiar versión» en 1 toque en el reproductor.
- **`name-index.json`**: tabla hash precalculada para resolver en **O(1)**:
  - `ytmChannels["UC..."]` → `artistSlug`
  - `ytmAlbums["MPREb_..."]` → `{ artistSlug, releaseId }`
  - `ytmVideos["<videoId>"]` → `{ artistSlug, workId, libraryPath }` (sustitución instantánea al abrir cualquier pista en YouTube Music)
  - `artists[alias/soundKey]` → `artistSlug`
  - `anime[alias/soundKey]` → `animeId`
  - `songs["artistSlug|titleKey"]` → `workId`
