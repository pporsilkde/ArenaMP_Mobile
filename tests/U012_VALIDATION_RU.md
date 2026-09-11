# U012 — восстановление Android Server и отдельная карточка названия

## Изменения

1. Распаковка server assets перенесена из UI-потока ServerActivity в фон.
   Ошибка остаётся на экране с точным путём ресурса, путём Update.log и кнопкой
   «Повторить». Периодический refresh не обращается к неинициализированным полям.
   Перехватываются также ошибки загрузки/чтения настроек экрана.
2. Для ServerActivity возвращены MyTheme и обычные Android AlertDialog.
   Цветовая палитра сохранена; остальные окна U011 не откатываются.
3. Копировщик читает таблицу ZIP установленного APK. Тип файла определяется
   по ZIP-записи и родительским путям, а не по результату AssetManager.list().
   Поддерживаются сжатые файлы, файлы нулевой длины, пустые и неявные каталоги,
   пути с пробелами/Unicode, base APK и split APK.
4. Server staging держит APK открытым на время копирования. Временные файлы
   проверяются по размеру/CRC и публикуются через rename. Ошибка указывает
   точный путь: например `APK is missing required asset: arenamp-server/...`.
5. FileLock сериализует установку между лаунчером и :arenamp_server: они не
   очищают одновременно один staging и не применяют одновременно один журнал.
6. До замены scripts/resources проверяются serverCore.lua, config.lua,
   resources/version и tes3mp-server-default.cfg. Если отсутствует только
   служебный runtime-stamp.txt, используется fingerprint APK.
   Отсутствующие обязательные файлы не подменяются пустышками.
7. server/data не заменяется целиком: существующие игроки/мир/config сохраняются.
   Отсутствующие default-файлы добавляются, runtime-каталоги создаются явно.
8. CI проверяет ресурсы и native-библиотеки внутри готового APK после
   фильтрации/сжатия Gradle, а не только в исходной папке assets.
9. Название сервера/build.name в Android-лаунчере вынесено в отдельную верхнюю
   стеклянную карточку на всю ширину. Название переносится на строки без
   фиксированного ограничения числа строк и без ellipsis. Changelog, сайт и
   обновление находятся в своей панели ниже; они не отнимают ширину у названия.
   Оставшееся пространство отдано содержимому лаунчера через layout_weight.

## Проверено локально

- Компиляция Java с target 8: ApkAssetArchive, AssetInstallLock, AssetTransaction,
  ContentUpdate и JVM-тесты.
- ApkAssetArchiveTest: сжатые/нулевые файлы, явные/неявные каталоги, сохранение
  игрока и config, замена managed scripts, новые defaults, split-overlay и
  fingerprint, точные ошибки отсутствующих/небезопасных путей, блокировка
  второго JVM-процесса до освобождения installation lock.
- AssetTransactionTest: 12 проверок восстановления и применения транзакций.
- ContentUpdateTest: 26 проверок.
- Kotlin 1.3 / JVM 1.8: проверка типов изменённых ServerActivity, ServerRuntime,
  AssetUpdater и APK-адаптеров против Android API 23. Отсутствующие зависимости
  проекта/AppCompat заменялись заглушками только для этой проверки типов.
  Это НЕ полная Android-сборка и НЕ тест поведения Activity на устройстве.
- XML-ресурсы и workflow YAML разобраны парсерами.
- Проверка final APK протестирована на синтетических полном/неполном ZIP.
- Разделение title/actions проверено по структуре layout и привязке build.name;
  реальное отображение на Android не проверялось.

Полный проект, проблемный APK и точный Update.log в запросе не предоставлены.
Вылет при необработанной ошибке assets подтверждён по коду; конкретный ресурс,
который не читался на устройстве, без лога неизвестен. Запуск JNI-сервера на
реальном Android и полная сборка APK здесь не проверялись.

После применения кумулятива пересоберите и установите APK поверх существующего
с той же подписью. Удаление данных приложения или сброс мира не требуется.
Откройте Server, дождитесь подготовки и запустите сервер. При повторной ошибке
передайте весь Update.log по пути на экране и, по возможности, установленный APK.
Если APK действительно не содержит server assets, нужна пересборка с полным
серверным payload: код не может восстановить отсутствующие в APK файлы.

## Команды JVM-тестов из корня Android

```sh
mkdir -p build/arena-asset-tests
javac -source 8 -target 8 -d build/arena-asset-tests \
  app/src/main/java/file/ContentUpdate.java \
  app/src/main/java/file/AssetTransaction.java \
  app/src/main/java/file/ApkAssetArchive.java \
  app/src/main/java/file/AssetInstallLock.java \
  tests/ContentUpdateTest.java tests/AssetTransactionTest.java tests/ApkAssetArchiveTest.java
java -cp build/arena-asset-tests ApkAssetArchiveTest
java -cp build/arena-asset-tests AssetTransactionTest
java -cp build/arena-asset-tests ContentUpdateTest
python3 tests/verify_server_apk.py app/build/outputs/apk
```

Справка Android API: [AssetManager.list](https://developer.android.com/reference/android/content/res/AssetManager#list(java.lang.String)).

## Сайт сервера

Кнопка сайта в Android и PC берёт непустой `url` из `build.com` рядом с build.ini;
если такого значения нет — из обычного `build.ini`. Если оба значения отсутствуют
или пусты, открывается `https://t.me/arena_mp`. Адрес без схемы (`t.me/arena_mp`)
открывается через HTTPS. `url_check` и адреса пакетов обновления не меняются.
`build.com` используется только как текстовый источник ссылки, не исполняется и
не заменяет основной build.ini для обновлений/контента.

```ini
[Build]
url=t.me/arena_mp
```
