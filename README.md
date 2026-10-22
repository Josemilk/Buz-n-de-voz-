# Cinta — buzón de voz

Contestador para Android, escrito en **Kotlin** y **Jetpack Compose**. Funciona **sin internet**: los saludos, los filtros de voz y los mensajes se quedan en el teléfono.

## Qué hace

- Elige después de cuántos timbres contesta (1 a 8). Cada timbre cuenta 5 segundos de llamada, porque Android no entrega el timbre uno por uno.
- Graba un saludo, elige cuál usar, lo importa desde un audio del teléfono o lo elimina.
- Ajusta la voz con limpieza, calidez, claridad, aire, de-ess, nivel y sala. Hay preajustes y un botón **Arreglar voz** que mide la toma y propone filtros.
- Guarda el historial de mensajes para escucharlos o borrarlos.
- Puede generar una voz de prueba en el propio aparato, o usar la voz local del teléfono si ya está instalada.
- La prueba del buzón (Mamá, Trabajo, Desconocido) no necesita una llamada real.

## Llamadas reales

Para descolgar, Cinta tiene que ser la **aplicación de teléfono** del sistema. En Ajustes está el botón para elegirla. Algunos fabricantes no permiten que una app reproduzca audio dentro de la llamada ni grabe la voz de la otra persona. Si pasa eso, el contestador avisa y el resto de la app sigue funcionando.

No pide permiso de internet y no lo declara.

## Compilar la APK en GitHub Actions

Cada push a `main` ejecuta [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml).

1. Abre la pestaña **Actions** del repositorio.
2. Entra en la ejecución **Compilar APK**.
3. Descarga el artefacto **Cinta-release** (firmada, lista para instalar) o **Cinta-debug**.
4. En el teléfono, permite instalar desde esa fuente y abre el archivo `.apk`.

La firma de release la crea el workflow en cada ejecución con una clave pública de distribución (`android` / alias `cinta`). Sirve para instalar desde Actions. No la uses como clave de Play Store: genera la tuya y guárdala en los secretos del repositorio.

## Compilar en Android Studio

1. Abre esta carpeta.
2. Usa JDK 17.
3. `./gradlew assembleDebug`

La APK de depuración queda en `app/build/outputs/apk/debug/`.
