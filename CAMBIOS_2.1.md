# VeltraX 2.1

## Android

- Reproducción aleatoria y repetición de una canción o de toda la cola.
- Pausa al desconectar los audífonos y gestión del foco de audio para convivir con otras aplicaciones.
- La notificación de reproducción abre VeltraX.
- Mensajes visibles si no se puede conectar al reproductor o reproducir un archivo.
- Actualización de la biblioteca al regresar a la aplicación, incluidos cambios de permiso en Ajustes.
- Solicitud del permiso de notificaciones en Android 13 o posterior.
- Reproductor ampliado con desplazamiento para pantallas pequeñas.
- Las descargas esperan conexión a una red antes de empezar.
- Corrección del tipo MIME de los archivos AAC y WMA al guardarlos.

## Motor compartido y compilación

- Corrección de reanudación cuando una pausa llega después del último byte y antes de guardar el archivo.
- Pruebas automatizadas HTTP para descargas segmentadas, servidores sin rangos, pausa al último byte y rechazo de respuestas HTML. Los datos de estas pruebas son sintéticos: comprueban la transferencia, no los códecs de reproducción.
- Gradle Wrapper completo y comprobación SHA-256 de la distribución.

Se mantienen el identificador Android `com.veltrax.music`, la música local y las descargas de enlaces directos. No se añadió un buscador de catálogos online ni extracción de audio de páginas web.
