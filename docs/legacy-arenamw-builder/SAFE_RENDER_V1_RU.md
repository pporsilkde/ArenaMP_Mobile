# ArenaMW Android — Safe Render V1

Цель этой версии — получить сначала корректную базовую картинку на Android, не ломая уже работающий запуск.

Изменения:

- На Android временно отключены только `osg::OcclusionQueryNode` запросы солнца. Само солнце/небо остаются, но sun glare/flash не используют проблемный OSG query callback. Это убирает `osgOQ: QG: Invalid RQCB` без переноса framebuffer callbacks ArenaMW.
- На Android при запуске принудительно включён безопасный legacy render baseline:
  - `force shaders = false`
  - `force per pixel lighting = false`
  - `lighting method = legacy`
  - Enhanced PBR и auto normal/spec maps выключены
  - shader water/refraction выключены
  - shadows выключены
- NG-GL4ES остаётся в ранее рабочем `LIBGL_SIMPLE_SHADERCONV=1`.
- Логи GL/OpenMW остаются включены.
- Инкрементальная сборка pporsilkde/AMW и все предыдущие CI fixes сохранены.

Это намеренно не финальное качество. V1 нужна как чистая контрольная точка: если мир, небо, альфа-листва и материалы становятся нормальными, дальше возвращаем по одному shader water → shadows → world shaders/PBR → HDR/Bloom и сразу видим, какой конкретный этап несовместим с Android.
