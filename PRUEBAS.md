# Verificación de VeltraX 3.0

15 de noviembre de 2026. El APK entregado es `com.veltrax.music`, versión 3.0, código 5, con mínimo Android 10 y objetivo Android 15. Es universal y contiene 77,039,512 bytes. La firma es la de desarrollo incluida en la 2.1. Se comprobó con apksigner, aapt y zipalign. El SHA-256 del APK es `6265a2b3a3cae7600b1d2b5d146588c3c9f2494f5cbc035ceee19054e1d242ee`.

## Comprobaciones realizadas

| Área | Comprobación |
| --- | --- |
| Compilación | Se generaron el APK de la aplicación y el APK de instrumentación. |
| JVM | Siete pruebas del motor de descargas pasaron. Cubren bytes de salida idénticos en descarga segmentada y normal, reanudación después del último byte, rechazo de HTML, almacenamiento insuficiente por inyección de capacidad, rechazo HTTP Range durante la reanudación y códigos HTTP 404/503. |
| Android Lint | 0 errors, 51 warnings. Los recursos heredados y las recomendaciones del analizador se detallan en el XML. |
| Actualización | Android aceptó la instalación sobre VeltraX 2.1. Se comprobó la coincidencia de las huellas de certificado. |
| Transferencias reales en Android 10 x86 | El servidor local entregó WAV mediante HTTP. Se iniciaron dos transferencias simultáneas, se pausó y reanudó una y se comparó el SHA-256 del resultado leído de MediaStore. |
| Fallos y recuperación | La prueba rechazó la URL inválida y el duplicado, cortó intencionadamente una conexión y comprobó los bytes al recuperarse. HTTP 404 terminó al primer intento. HTTP 503 terminó en error con cuatro intentos. |
| Playlists | El parser importó 1 200 referencias de un M3U remoto. |
| Segundo plano | Media3 reprodujo el WAV descargado. La posición continuó avanzando con la actividad en segundo plano. |
| Reinicio de Android | El ensayo de reinicio no completó la verificación de extremo a extremo. El informe del ejecutor recoge la última fase alcanzada. |
| Catálogo | Se consultó Audius con «bachata» y se recibieron metadatos reales incluyendo el indicador de disponibilidad. |

## Alcance

Las pruebas usaron un emulador Android 10, con respuestas HTTP controladas para los casos de fallo. Falta validar en un teléfono Android 16 la política de batería del fabricante, el efecto acústico del ecualizador, el crossfade, los controles de bloqueo y la estabilidad durante sesiones prolongadas. La consulta interna de YouTube requiere una clave y no se verificó con una cuenta personal. La simulación de falta de espacio prueba la rama de error; no se llenó un teléfono real. El editor modifica las etiquetas de la biblioteca de VeltraX. La edición de etiquetas dentro del archivo, un visualizador reactivo y un instalador actualizado de Windows no forman parte de este APK.

Los resultados están en `verificacion/`. El servidor reproducible y el procedimiento de pruebas Android están en `development/device_checks.py` y en el README del proyecto.
