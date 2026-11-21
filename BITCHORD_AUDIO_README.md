# BitChord Audio: resumen del fork y estado actual

*Actualizado: 21 nov 2026 · última versión publicada: **v1.9.285***

Repositorio: `JuanmiRodas/Musichord`. Es el fork «BitChord Audio» de BitChord, una app Android en Kotlin con Compose y Media3, organizada en los módulos `shared`, `sharedUi` y `app`.

Este documento resume las modificaciones hechas sobre BitChord y el punto en que está el trabajo. Las notas anteriores del fork están en `FORK_NOTES.md`, dentro del repositorio: merge de upstream v1.9.1, port de MusicBeat y LastWave, sistema de letras, Cast, TV, etc.

---

## 1. Reglas de trabajo (para cualquier sesión nueva)

- **Nada de canciones a mano en el código.** Toda corrección tiene que ser genérica. Se mantienen todas las funciones propias del fork.
- **Datos calculados guardados en el móvil** y copiados a pCloud a través de `CloudBackup` / `SettingsSync.FILES`.
- **Respuestas en español.** Antes de un trabajo grande y ambiguo, preguntar.
- **IA siempre gratuita: nunca un modelo de pago.**
- **Seguridad:**
  - No usar workflows de CI que ejecuten scripts arbitrarios (fue denegado).
  - No falsificar `AddonAuth`.
  - No repetir el WebFetch a lyricfind.
- **Privacidad:** los correos del usuario no se comparten fuera.
- **Esperas cortas.** Al usuario no le gustan las esperas largas de CI.
- **Trabajo pesado en segundo plano solo de noche** (01:00–07:00, con `BackgroundHours`). Excepción: «Analizar ahora» sigue hasta terminar y solo se para si se pierde el WiFi.

### Compilar y publicar

- **Commit:**
  `git -c user.name=Claude -c user.email=noreply@anthropic.com commit -m "…"`
  El mensaje termina con las líneas `Co-Authored-By` y `Claude-Session`.
- **Push:** `git push https://github.com/JuanmiRodas/Musichord.git HEAD:main`
- **CI:** `.github/workflows/audio.yml` tarda unos 17–20 min y publica una release `v1.9.x` (APK arm64).
- **Comprobar CI:** `gh run list -R JuanmiRodas/Musichord -L 1`
- **Errores de compilación:**
  `gh run view ID --log-failed | grep "e: file:///home/runner/work/Musichord/Musichord/"`
  - Las líneas «incompatible version» de Kotlin-metadata son ruido.
  - Un fallo al instalar el NDK es un problema puntual de CI: `gh run rerun`.
- **Textos de la app:** en `values/strings.xml` y `values-es/strings.xml`. No usar apóstrofes en los textos en inglés, porque rompen aapt.
- **Registros del móvil:** repositorio `bitchord-logs`, ruta `logs/OnePlus-CPH2747/<fecha>/<hora-inicio-proceso>.log`.
  - Hacer `git pull` antes de leerlos.
  - El nombre del archivo es la hora en que arrancó el proceso, así que una sesión que empezó ayer sigue en la carpeta de ayer.

---

## 2. Qué se ha cambiado (por áreas)

### 2.1 Comprobación de audio de los enlaces («Shazam»)
- Antes de dar por bueno un enlace a un archivo propio, la app lo escucha con una huella acústica (Haitsma–Kalker).
  - Bit error rate < 0,33 = misma grabación; > 0,42 = distinta.
- Corre en segundo plano y tiene su propia pantalla: estado, «Analizar ahora», pausa, historial «Hecho / Pendiente».
- **Horario:** horas, redes WiFi elegidas, solo cargando.
  - Por defecto: de noche (01:00–07:00), cargando y nunca en ahorro de batería.
- Archivo: `playback/smart/AudioCheck.kt` (`audio_checks_history.json`, `AudioCheck.FILE`).

### 2.2 «Aparece en», familias de canciones y metadatos
- **Otras interpretaciones:** cuentan los artistas relacionados (unidades, proyectos) y las versiones en directo de la biblioteca.
- **Mapa de familias:** se calcula en segundo plano y se guarda en `song_family.json` (`data/SongFamily.kt`).
- **`MetadataFill`:** rellena etiquetas de descargas y archivos de pCloud ya confirmados.
- **«Artista desconocido»:** se deduce del contexto y se escribe en el archivo.

### 2.3 Vista de álbumes y biblioteca
- **Coincidencia estricta de artista** (primer crédito, nombre completo):
  - Aparecen los álbumes en directo con me gusta.
  - Se excluyen álbumes de otros artistas (YUKI, Revo, See-Saw) y archivos basura.
  - NARUTO ya no sale bajo Yuki Kajiura.
- La nube se muestra con un icono, no con emoji.
- **Rendimiento:** las claves de nombre (lecturas japonesas, claves de sonido y de álbum) se calculan una sola vez y se guardan. Antes la pestaña Biblioteca ocupaba un núcleo durante minutos en cada arranque.

### 2.4 Reproductor
- Créditos centrados sobre la portada.
- Arreglado un bloqueo (ANR): la consulta de portadas elegidas ahora va con caché.
- **Vídeo más ligero:** 720p por defecto, vigilante de 12 s para el primer fotograma, 15 s de margen para resolver el vídeo.
- **Efecto de carga:** una luz discreta que recorre el borde de la portada.
- **Portadas elegidas por versión:** la clave es título + artista + álbum.
- **«Canción empieza dos veces»:** arreglado.
  - Ya no se hace un segundo cambio de copia encima del cambio a tu archivo de pCloud.
  - Un cambio entre copias sin desfase conocido es un corte de 40 ms, no un solape de 550 ms.

### 2.5 Limpieza de la biblioteca de pCloud y asistente
- **Tarjetas por carpeta de álbum.**
  - Las propuestas salen de YouTube Music o de la IA.
  - Al aplicar se escriben etiquetas, portada y letra.
  - Los archivos sin pérdida pasan a AAC 320 y el original va a `_Archivo BitChord`.
  - Nunca propone karaoke ni covers; las carpetas sueltas se tratan canción por canción.
- **Asistente, fase 1:**
  - Trabaja solo de noche y dentro de un presupuesto.
  - Escribe cuando está seguro (se puede deshacer) y deja tarjetas editables cuando no.
  - Hace preguntas, con aviso en la foto de perfil.
  - Tiene un chat en lenguaje natural para excluir carpetas, dar pistas, notas o rehacer.
- **Asistente, fase 2:** detecta vídeos con me gusta que no son la canción. Mueve el like a la canción oficial o corrige los datos del vídeo.
- **«Limpiar con el asistente»** (menú de canción y de álbum):
  - Ventana con los pasos y la propuesta en vivo, editable.
  - Usa la descripción del vídeo y los nombres de archivo como contexto.
  - Acredita al intérprete de esa grabación (p. ej. FictionJunction).
  - Elige la portada más grande de la misma imagen, comparando con hash de imagen.
  - Distingue directo de estudio («(Live at …)»).
  - Guarda en el comentario dónde se usó la canción (OP/ED de anime, juegos).
- Archivos: `data/cleanup/` (`LibraryCleanup`, `Assistant`, `LikedVideos`, `CleanNow`, `CoverMatch`, `Transcoder`).

### 2.6 Proveedores de IA (siempre gratis)
- **Gemini** (clave propia):
  - Los modelos se eligen de la lista de Google.
  - Ante 429/503 pasa al siguiente modelo y después a los otros servicios.
- **Experiential Labs y Mistral** (compatibles con OpenAI) como respaldo, cada uno con botón de prueba y selector de modelo.
- **Experiential: solo modelos gratuitos.**
  - Cuentan como gratis los de precio 0, los que acaban en `:free`, y Jev, Clef y GPT-6 Luna.
  - **Nunca DeepSeek V4.1 Flash, que es de pago**: generó un cargo de $1,08.
  - El selector solo ofrece modelos gratuitos.
- Archivos: `data/cleanup/Gemini.kt`, `OtherBrains.kt`. Las claves están en las prefs `bitchord_assistant`, que no se sincronizan.

### 2.7 Me gusta
- **Portadas para likes locales sin portada**, buscadas en este orden:
  1. El archivo.
  2. La descarga.
  3. Portadas elegidas a mano.
  4. La biblioteca.
  5. Otro archivo de la misma carpeta.
  6. YouTube Music.
  7. iTunes.
- **«Convertir en versión canción» / «Restaurar el vídeo original»** en el menú ⋯. Busca primero en YouTube Music, después en tu archivo de pCloud y si no, en el selector de versiones.
- **Quitar un like** quita también su copia gemela en Me gusta.
- **Un like por canción:**
  - No se da like dos veces a dos archivos de la misma canción (mismo título en cualquier alfabeto, mismo artista, ±3 s de duración).
  - Los duplicados existentes se limpian solos. Se conserva primero la copia descargada; los likes de YouTube no se tocan.
  - Origen del problema: la carpeta *Garnet Crow/REQUEST BEST* tiene cada canción dos veces y se dio like al álbum entero.
- **Los likes siguen a los archivos movidos o renombrados en pCloud** (`LocalLikes.relink`).
- Se quitaron una vez las versiones karaoke de Me gusta.

### 2.8 Búsqueda
- Las canciones con me gusta y la música propia salen primero en «Todo».
  - El fallo era que «Todo» es `SearchFilter.ALL`, no `null`.
- Los vídeos también salen en «Todo», y la búsqueda en la música propia va en paralelo.

### 2.9 Modo vídeo
- **Vídeo reconstruido para la canción** (`VideoAligner.rebuild`, archivo `video-sync-v3.json`):
  - Compara croma parte por parte.
  - Cada parte de la canción se sincroniza con la parte del vídeo que suena igual, aunque el estribillo se repita.
  - Si la intro es más larga, el vídeo se repite en bucle hasta coincidir.
  - Si no coincide nada, muestra un tramo al azar en bucle.
  - Se guarda y se copia a pCloud.
- **Saltos fundidos:** el último fotograma queda encima y se desvanece sobre la nueva posición.
- **Fuentes de vídeo:**
  - **YouTube.**
  - **AnimeThemes** (openings y endings de anime):
    - Se consulta por GraphQL en `graphql.animethemes.moe`, porque la API REST responde 503.
    - Coincidencia flexible: Rica/Rika, kanji/romaji, créditos de personaje.
    - Se descarga en una sola conexión, con reintentos.
    - Se sincroniza con su audio Ogg aparte.
  - **bilibili.**
  - **Tus vídeos de pCloud:**
    - Solo en las carpetas que elijas, y solo se busca al pulsar buscar.
    - También se puede explorar carpeta a carpeta.
    - La miniatura se guarda en pCloud junto al vídeo, como `<nombre>-thumb.jpg`.
- **Selector unificado:** una hoja con un único buscador y una pestaña por fuente (`ui/components/VideoSearchDialog.kt`).
- **Más rápido:**
  - Las búsquedas van en paralelo.
  - El vídeo de la siguiente canción queda preparado, salvo con datos móviles o en ahorro de batería.
- **Ajustes visuales:**
  - Sin letra no hay sombra de letra.
  - Si se decide que hace falta sombra, no se vuelve a comprobar en 30 s.
  - El zoom de las bandas negras y la sombra se guardan con la primera lectura y se copian a pCloud.
- **Ideas tomadas de LastWave:** su «base de datos» es SponsorBlock (que ya se usaba) y su rapidez viene de hacer menos trabajo.

### 2.10 Batería y rendimiento
- **Chivato de CPU** en el registro: cada minuto, si la app usa más del 10 % de un núcleo, apunta los hilos y el código que se ejecuta (`MemoryWatch`).
- **Cálculos repetidos** que ahora se hacen una sola vez y se guardan:
  - Lecturas japonesas, claves de sonido y de álbum.
  - Lectura de nombres en las páginas de artista.
  - Revisión de vídeos con me gusta.
- **Menos trabajo periódico:**
  - Los widgets no se redibujan con la pantalla apagada.
  - El fundido entre canciones comprueba a 1 Hz en pausa.
  - La letra de la notificación se comprueba cada 5 s con la pantalla apagada.
  - Las descargas consultan 4 veces menos.
  - Las portadas no encontradas no se buscan otra vez hasta pasada una semana.

### 2.11 pCloud: biblioteca y copias
- **Biblioteca:** si la carpeta elegida es todo el disco, la app recorre las carpetas donde estaba tu música. Deja fuera la carpeta `Music/` del espejo del móvil.
  - **Importante:** desde el 19 de noviembre la biblioteca no se actualizaba, por una protección contra recorrer todo el disco. Corregido en v1.9.283.
- **Copia espejo del móvil** (`data/webdav/PhoneBackup.kt`):
  - Toda la música del teléfono (descargas y archivos propios) va a `pCloud/Music/`, con las mismas carpetas y nombres.
  - Los archivos van tal cual, sin renombrar ni etiquetar.
  - Solo con WiFi y sin ahorro de batería. Nunca sube dos veces el mismo archivo y nunca borra nada.
  - Se activa o desactiva en Ajustes.
  - La idea es limpiar después la biblioteca en el Mac con una app propia.

### 2.12 ADN de la canción (como SongDNA de Spotify)
- **Dónde:** bajo el reproductor. Se desliza hacia arriba desde los controles (o ⋯ → «Información de la canción»); con la lista arriba del todo, deslizar hacia abajo devuelve el reproductor. Desde el menú de cualquier otra canción se abre la misma página en una ventana.
- **Qué muestra:** sobre la canción (Wikipedia / Genius / Last.fm / Discogs), créditos agrupados (interpretación, composición y letra, arreglos, producción, sonido), conexiones (samples, interpolaciones, versiones, remixes, directos), otras versiones (`SongFamily`), dónde aparece (álbumes, temas de anime de AnimeThemes), portadas alternativas (→ selector de portada), detalles (álbum, fecha, sello, géneros, BPM, idioma, ISRC, oyentes), tu copia (ruta en pCloud / en el móvil, tamaño, formato) y la descripción del vídeo.
- **Fuentes gratuitas:** créditos de YouTube Music (`MPTC` + id), MusicBrainz (+ obra) y Cover Art Archive, Wikidata/Wikipedia (es, en, ja), Genius (API web sin clave), Last.fm, iTunes, Deezer (BPM), AnimeThemes y **Discogs con token propio** (se pega en la propia página, «Añadir más fuentes»; prefs `bitchord_dna_keys`, no se sincronizan).
- **Datos:** `song_dna.json` (`SongDna.FILE`), copiado a pCloud por `CloudBackup`. Relleno de toda la biblioteca de noche con `BackgroundHours` y WiFi (`SongDna.fillAll`); de día solo al abrir la página.
- Archivos: `data/dna/SongDna.kt`, `data/dna/DnaKeys.kt`, `ui/components/SongDnaPage.kt`; el panel en `NowPlayingScreen` (`PlayerHost.SongDnaPanel`).

### 2.13 Descargas de listas («Música que te gusta»)
- Arreglado en v1.9.284: la sincronización en segundo plano no incluía los likes locales (pCloud) y los borraba 30 s después de descargarlos.

---

## 3. Dónde estamos ahora (v1.9.283)

**Recién publicado, pendiente de comprobar en el móvil:**
- Que la biblioteca vuelva a listarse y que los likes de archivos movidos se reenlacen. Casos a probar: los archivos de Harvest («Gatherway», «Bokura no Bouken», «Kaze no Mirai e»), «OK!», «Ready Go!», «月ひとつ».
- Que esas canciones vuelvan a sonar, se descarguen y tengan portada.
- Que la copia espejo suba bien. En la madrugada del 21 hubo fallos de red y algún tiempo de espera agotado.
- Que AnimeThemes reproduzca bien varios vídeos seguidos (ED4/ED5 de Pokémon).

**Propuesto y aplazado por el usuario** (lo revisará con calma). Al empezar una canción:
- El romaji automático vibra hasta 4 veces.
- La letra se cambia a mitad de canción cuando aparece una mejor.
- Se escribe en el archivo que está sonando, lo que reinicia el audio.
- La búsqueda de letra se hace dos veces.
- Se reintenta sin parar un dato de YouTube para el historial (`signature timestamp`).

Arreglos propuestos: que el romaji automático no vibre, guardar la letra mejor para la próxima vez, no escribir en el archivo mientras suena, y limitar búsquedas y reintentos.

**Otros puntos abiertos:**
- En cada arranque, «downloads: moved 0 of 50 to Music/BitChord»: intenta mover 50 descargas y no mueve ninguna.
- La copia del 21 nov de «Música que te gusta» a pCloud: 266 subidas y 1536 fallidas. Probablemente por la biblioteca sin actualizar.
- Quién renombró los archivos de pCloud. La app no renombra; parece una reorganización hecha desde el Mac.
- Proyecto futuro: una app para el Mac que limpie la biblioteca y la mantenga sincronizada con pCloud y con el ordenador.

---

## 4. Archivos de datos y copia de seguridad

| Dato | Dónde | Copia en pCloud |
|---|---|---|
| Lista de archivos copiados por `SettingsSync.FILES` | `lyric_aliases.json`, `OfficialNames.FILE`, `SongFamily.FILE`, `AudioCheck.FILE`, `LibraryCleanup.FILE`, `Assistant.FILE`, `LikedVideos.FILE` | sí |
| Sincronización de vídeo | `video-sync-v3.json` (`VideoAligner.FILE`) | sí (`CloudBackup`) |
| Bandas negras / sombra de cada vídeo | prefs `bitchord_video_mode` | sí, en segundos |
| Likes locales | prefs `bitchord_local_likes` | sí, al momento |
| Carpetas de vídeo de pCloud e índice | prefs `bitchord_pcloud_videos`, `pcloud_videos.json` | no (se rehacen) |
| Miniaturas de vídeos de pCloud | junto al vídeo en pCloud (`-thumb.jpg`) | están en pCloud |
| Estado de la copia espejo | prefs `bitchord_phone_backup` | no |
| Claves de IA | prefs `bitchord_assistant` | **no** (privadas) |
