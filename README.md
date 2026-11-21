# VeltraX 3.0

Reproductor musical nativo para Android 10 o superior. Esta edición actualiza el proyecto 2.1, conserva el identificador `com.veltrax.music`, la clave de desarrollo, las playlists y los favoritos existentes. El ZIP incluye el código del módulo de Windows heredado. El APK incluye los recursos originales y las cuatro arquitecturas del proyecto.

## Descargas

En **Descargas**, pega un enlace y pulsa **Revisar enlace**. Selecciona pistas o formatos y pulsa **Descargar**. También puedes compartir un enlace desde un navegador. La aplicación guarda el archivo de origen íntegro. En **Ajustes → Descarga** puedes elegir entre uno y cuatro archivos simultáneos, restringir a Wi-Fi o seleccionar una carpeta SAF. El destino inicial es Music/VeltraX para audio y Movies/VeltraX para video. Para guardar archivos propios no hace falta conceder acceso a la música de otras aplicaciones.

Puedes pausar, continuar, cancelar, dar prioridad, quitar elementos del historial, agrupar por playlist y aplicar operaciones a una selección. Los estados muestran los bytes, el tamaño cuando lo informa el servidor, la velocidad medida y el motivo de fallo. La reanudación reutiliza partes con un validador HTTP. Un servidor que cambia su compatibilidad con Range puede obligar a descargar de nuevo. Las URLs se deduplican antes de arrancar el trabajo. Los archivos se incorporan a Biblioteca después de publicarse.

**Diagnóstico de la 2.1.** La versión anterior mostraba «En cola» también durante los reintentos. El worker reintentaba indiscriminadamente errores de origen y de publicación e intentaba insertar en el volumen agregado externo. La causa concreta en un teléfono determinado depende del enlace y del registro. Esta edición inicia un servicio de transferencia desde la acción del usuario, registra los trabajos en SQLite, publica en el volumen primario y utiliza WorkManager para recuperar trabajo persistido. Muestra errores permanentes y limita a cuatro los intentos por fallo transitorio mientras haya conexión. Android puede aplazar una tarea en segundo plano. Después de forzar la detención de la aplicación desde los ajustes de Android hay que abrirla de nuevo. Para ver diagnósticos usa `adb logcat -s VeltraXQueue`.

## Fuentes y biblioteca

| Fuente | Funciones disponibles |
| --- | --- |
| Audius | Buscar pistas, artistas, álbumes o playlists, reproducir en Media3 y guardar enlaces. Se ofrece el archivo original cuando el catálogo autoriza la descarga pública. |
| Internet Archive | Buscar audio con licencia declarada, abrir colecciones, elegir entre los archivos y formatos publicados. |
| YouTube | Buscar y reproducir a través de la aplicación o la web oficial. Una clave propia opcional de YouTube Data API en Ajustes permite buscar dentro de VeltraX y expandir listas públicas. YouTube gestiona su reproducción y opciones offline. Para descargar en VeltraX utiliza un archivo directo autorizado por quien lo publica. |
| Archivos y listas | Abrir archivos directos HTTP/HTTPS, audio o MP4. Importar listas M3U/M3U8 con referencias absolutas o relativas. Los manifiestos HLS se identifican al importar. |
| Letras | Consultar LRCLIB por artista y título, importar texto o LRC, resaltar la línea sincronizada y saltar a su posición al tocarla. |

**Inicio** muestra accesos rápidos, recientes, favoritos y pistas del artista que has escuchado. **Buscar** funciona incluso cuando la biblioteca está vacía. Permite filtrar, guardar enlaces, reproducir, descargar en masa y consultar el historial. **Biblioteca** reúne canciones, artistas, álbumes, carpetas, géneros, favoritos, frecuentes, archivos descargados y candidatos a duplicado. El editor de etiquetas guarda cambios de título, artista, álbum y género en el catálogo local.

En **Playlists** puedes crear, renombrar, borrar, elegir portada, seleccionar muchas pistas y reordenar las de una lista manual arrastrando el asa. Importa y exporta archivos M3U y M3U8. Las listas muestran las referencias ausentes. Las automáticas se actualizan por género, artista o frecuencia. Los enlaces de archivos locales pueden requerir volver a localizar la música en otro teléfono.

## Reproducción y apariencia

Media3 proporciona la reproducción en segundo plano, notificación, pantalla de bloqueo, widget, repetición y aleatorio. El mini reproductor abre la vista completa con cola editable y letras. En **Ajustes → Audio** están la velocidad, el temporizador, el ecualizador de la sesión real, el control de graves y agudos y los presets. El número de bandas y el soporte dependen de la salida. El crossfade usa dos reproductores para archivos locales que duren lo suficiente y tengan otra pista local a continuación.

Los cambios de apariencia se guardan de forma inmediata. Incluye tema del sistema, claro, oscuro y AMOLED, acento hexadecimal o de carátula, degradados, densidad y escala, tipografía, navegación inferior o lateral, tres estilos de reproductor, giro de la carátula, opacidad del mini reproductor y desenfoque desde Android 12. Puedes exportar o importar temas JSON, restablecer el aspecto, borrar historiales y exportar ajustes y biblioteca. Los archivos de audio siguen en el almacenamiento del dispositivo. Las copias de VeltraX excluyen la clave de YouTube.

## Compilación y pruebas

Abre la raíz en Android Studio con JDK 17 y Android SDK 35. El Wrapper fija Gradle 8.14.3. Para generar la edición distribuida y el APK de pruebas:

```sh
./gradlew :core:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :androidApp:lintDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
python3 development/device_checks.py --serial emulator-5554
```

En Windows usa `gradlew.bat`. El último comando necesita Python 3 y ADB. Con `--reboot` reinicia el dispositivo de pruebas indicado y verifica una transferencia persistida. Usa un dispositivo destinado a pruebas. Consulta **PRUEBAS.md** para los resultados de esta entrega. La firma de desarrollo se incluye para mantener la continuidad con VeltraX 2.1. Para publicar comercialmente se necesita una clave de distribución privada.
