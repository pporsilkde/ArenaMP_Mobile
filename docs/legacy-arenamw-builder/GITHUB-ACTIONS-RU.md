# ArenaMW Android — GitHub Actions

Этот комплект сделан как минимальная переделка рабочего `Android_ArenaMP_NG` под одиночный `ArenaMW`.

## Что собирается

- Исходник движка: `https://github.com/pporsilkde/AMW.git`
- Ветка по умолчанию: `main`
- ABI: `arm64-v8a`
- Native build: Release (`-O3`, ThinLTO — как в исходном builder)
- Android launcher: MainlineDebug, чтобы APK автоматически имел стандартную debug-подпись и сразу устанавливался
- Результат: `ArenaMW-arm64-release.apk` в GitHub Actions Artifacts

## Что удалено относительно ArenaMP

- RakNet ExternalProject и `libRakNetLibStatic.a`
- TES3MP native target и `libtes3mp.so`
- master/server/browser build flags
- `--connect IP:port`
- настройки IP/порта сервера в Android launcher
- переименование `libtes3mp.so -> libopenmw.so`

Сборщик сразу строит `openmw` и получает настоящий `libopenmw.so`.

## Почему две Java

Современный `sdkmanager` на GitHub runner требует JDK 17. Старый проверенный Android Gradle Plugin 4.0.2 из оригинального проекта оставлен без обновления и запускается с JDK 11.

Workflow поэтому делает:

1. JDK 17 -> только установка `platforms;android-29` и `build-tools;29.0.3`.
2. JDK 11 -> Gradle 6.1.1 / AGP 4.0.2.

Это исправляет ошибку из лога:

`This tool requires JDK 17 or later. Your version was detected as 11...`

## Кэш

Тяжёлые зависимости (`Boost`, `OSG`, `FFmpeg`, `SDL2`, `OpenAL`, `Bullet`, `MyGUI`, `LZ4`, `NG-GL4ES`) сохраняются отдельным cache checkpoint.

Перед каждой клиентской сборкой удаляется только `arenamw-prefix`, поэтому `ArenaMW:main` клонируется заново, но сторонние библиотеки не пересобираются.

## Запуск

Загрузить содержимое архива в отдельный GitHub-репозиторий (например `ArenaMW_Android`) и открыть:

`Actions -> ArenaMW Android APK -> Run workflow`

Workflow также запускается при push в `main`/`master` самого репозитория сборщика.

## Локально Linux/WSL2

```bash
sudo apt update
sudo apt install -y build-essential cmake curl git patch unzip gettext-base autoconf automake libtool pkg-config python3 ccache ninja-build
cd buildscripts
./build.sh --arch arm64 --ccache --release
cd ..
./gradlew assembleMainlineDebug
```

Для Gradle локально использовать JDK 11. Android SDK должен содержать Platform 29 и Build Tools 29.0.3.

## GitHub executable permissions

Workflow перед native-сборкой явно восстанавливает `+x` для `buildscripts/*.sh`, `gradlew` и вложенных shell-скриптов. Это нужно, если файлы были загружены в GitHub через веб-интерфейс или распакованы из ZIP: в таком случае Unix executable-bit может потеряться, и Linux runner выдаёт `Permission denied (exit code 126)`.

## Исправление зависания/падения на `Build ArenaMW native arm64` около 100%

В CI финальная строка CMake `100%` ещё не означает, что `libopenmw.so` полностью готов: после неё может продолжаться финальная ThinLTO-линковка и post-build обработка.

В этой версии сборщика:

- `BUILD_JOBS=2`, чтобы снизить пиковое потребление памяти;
- `LTO_JOBS=1`, чтобы ThinLTO не запускал несколько тяжёлых backend-задач одновременно на финальной линковке;
- debug-symbol indexing отключён в GitHub Actions (`GENERATE_DEBUG_SYMBOLS=false`), потому что эти символы не входят в APK и раньше только добавляли тяжёлую обработку после native build;
- при тишине линкера раз в 60 секунд печатается heartbeat, поэтому Actions больше не выглядит как бесконечно зависший на `100%`;
- при native failure автоматически создаётся artifact `ArenaMW-native-failure-<run>` с полным `arenamw-build.full.log`, CMake logs и состоянием runner;
- сообщения OOM (`Killed`, `out of memory`, `LLVM ERROR`, `signal 9`) теперь не скрываются compact-фильтром.

Если сборка снова упадёт, скачайте artifact `ArenaMW-native-failure-*`: в `arenamw-build.full.log` будет настоящий конец компиляции/линковки, даже если веб-интерфейс GitHub Actions его не показал.


## SAFE LINK mode (100% -> abrupt runner failure)

Workflow intentionally builds arm64 with `-O3` but with ThinLTO disabled (`LTO=false`) and `BUILD_JOBS=1`.
This avoids the highest-memory final `libopenmw.so` link. The native dependency cache key is `v6-nolto` so old ThinLTO objects are not reused.
When enough runner disk is available, CI also enables a 3 GiB swap file before the ArenaMW client link.
After a successful baseline APK, ThinLTO can be tested again separately.

### Swap для финальной линковки

Workflow сначала проверяет уже активный swap. Если GitHub runner уже имеет не менее 2 GiB swap, он используется как есть. Дополнительный swap создаётся только при необходимости и его настройка не может сама уронить сборку.
