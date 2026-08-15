# ArenaMW Android V14 — поведение build.ini как в ПК-лаунчере

## Что перенесено

- `build.ini` становится главным источником порядка подключаемого контента.
- Если `build.ini` существует, `content=` применяется ровно в записанном порядке, включая намеренно пустой список.
- `groundcover=` хранится отдельно от обычных ESP/ESM.
- `archive=` / BSA хранится отдельно.
- `language=` сохраняется каноническим идентификатором (English/Russian/Polish и т.д.).
- Неизвестные и старые сетевые поля игнорируются.
- Манифест сохраняется атомарно в UTF-8.
- Каноническое место — рядом с `Data Files` (`<Morrowind root>/build.ini`), старый `Data Files/build.ini` читается для совместимости.
- `data-path` по возможности записывается переносимо (`Data Files`), а не абсолютным Android-путём.
- При первом запуске без манифеста один раз применяется тот же канонический порядок контента, что в ПК ArenaMW, затем создаётся `build.ini`.
- После этого порядок больше автоматически не пересортировывается.
- Изменения включения/отключения и drag-and-drop порядка в Android Mods сразу сохраняются обратно в `build.ini` при выходе из менеджера модов.
- Перед запуском `build.ini` перечитывается снова: ручная правка файла извне не теряется.
- Android теперь принимает в выборе игры как корень Morrowind, так и сам каталог `Data Files`.

## Формат

```ini
# ArenaMW standalone portable build manifest
# Ordered entries are applied exactly as written.

[Build]
format=1
name="ArenaMW"
data-path="Data Files"
language="Russian"
complete=true

[Content]
content="Morrowind.esm"
content="Tribunal.esm"
content="Bloodmoon.esm"

groundcover="MyGrass.esp"

[Archives]
archive="Morrowind.bsa"
archive="Tribunal.bsa"
archive="Bloodmoon.bsa"
```

## Важное отличие от старого Android

Раньше источником истины была только SQLite `ModsDatabase`, а `openmw.cfg` каждый запуск строился из неё. Теперь схема такая:

`build.ini -> ModsDatabase/Launcher -> openmw.cfg`

а изменения пользователя идут обратно:

`Mods UI -> ModsDatabase -> build.ini`
