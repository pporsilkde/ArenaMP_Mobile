#!/usr/bin/env python3
# Mali Magic Stability X002 — cumulative ArenaMP Android patch
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit('usage: 12-arenamp-magic-mali-stability.py <ArenaMP source dir>')

root = Path(sys.argv[1]).resolve()


def load(rel: str) -> str:
    path = root / rel
    if not path.is_file():
        raise SystemExit(f'missing required source file: {rel}')
    return path.read_text(encoding='utf-8')


def save(rel: str, text: str) -> None:
    (root / rel).write_text(text, encoding='utf-8')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    # Prefer the old anchor whenever it is still present.  Checking for the
    # replacement first is unsafe for short/common snippets because an identical
    # replacement may legitimately exist elsewhere in the same translation unit.
    count = text.count(old)
    if count == 1:
        return text.replace(old, new, 1)
    if count == 0 and new in text:
        return text
    raise SystemExit(f'{label}: expected exactly one anchor, found {count}')


# 1) Summon creation in TES3MP creates a temporary local scene object only to
#    serialize ObjectSpawn, then deletes it. Harden empty/cell checks; Android
#    runs OSG SingleThreaded (launcher patch) so this immediate detach cannot race
#    a concurrent cull/draw traversal.
rel = 'apps/openmw/mwmechanics/summoning.cpp'
text = load(rel)
text = replace_once(
    text,
    '''                        if (mwmp::Main::get().getCellController()->hasLocalAuthority(*placed.getCell()->getCell()))\n''',
    '''                        if (!placed.isEmpty() && placed.isInCell() && placed.getCell()\n                            && mwmp::Main::get().getCellController()->hasLocalAuthority(*placed.getCell()->getCell()))\n''',
    'summoning.cpp placed cell guard',
)
text = replace_once(
    text,
    '''                        MWBase::Environment::get().getWorld()->deleteObject(placed);\n''',
    '''                        if (!placed.isEmpty())\n                            MWBase::Environment::get().getWorld()->deleteObject(placed);\n''',
    'summoning.cpp placed delete guard',
)
save(rel, text)


# 2) Summon despawn: collect/use all local data before sending ObjectDelete.
#    X002 deliberately patches only the cleanupSummonedCreature() function range
#    instead of matching its entire body byte-for-byte. ArenaMP main and local
#    cumulative patches may change comments/whitespace around this code; a full
#    1.3 KB text anchor made X001 fail before compilation with "found 0".
rel = 'apps/openmw/mwmechanics/actors.cpp'
text = load(rel)
fn_start_marker = '    void Actors::cleanupSummonedCreature (MWMechanics::CreatureStats& casterStats, int creatureActorId)\n'
fn_end_marker = '    void Actors::purgeSpellEffects(int casterActorId)\n'
fn_start = text.find(fn_start_marker)
fn_end = text.find(fn_end_marker, fn_start + 1) if fn_start >= 0 else -1
if fn_start < 0 or fn_end < 0:
    raise SystemExit('actors.cpp network summon lifetime: cleanupSummonedCreature function boundaries not found')

fn = text[fn_start:fn_end]
patched_marker = '// X002: Send ObjectDelete last. Do not dereference ptr afterwards.'
if patched_marker not in fn:
    if_start = fn.find('        if (!ptr.isEmpty()')
    else_marker = '        else if (creatureActorId != -1)\n'
    else_pos = fn.find(else_marker, if_start + 1) if if_start >= 0 else -1
    required = (
        'objectList->sendObjectDelete();',
        '.search("VFX_Summon_End")',
        'creatureMap.clear();',
    )
    if if_start < 0 or else_pos < 0 or any(item not in fn[if_start:else_pos] for item in required):
        raise SystemExit('actors.cpp network summon lifetime: function shape is unsupported; refusing unsafe partial patch')

    replacement = r'''        if (!ptr.isEmpty() && ptr.isInCell() && ptr.getCell() && ptr.getCell()->getCell()
            && mwmp::Main::get().getCellController()
            && (casterStats.getActorId() == getPlayer().getClass().getCreatureStats(getPlayer()).getActorId()
                || mwmp::Main::get().getCellController()->hasLocalAuthority(*ptr.getCell()->getCell())))
        {
            // Finish every read/recursive cleanup before the network delete can
            // invalidate this Ptr on a fast local/server echo path.
            const osg::Vec3f despawnPosition = ptr.getRefData().getPosition().asVec3();

            MWMechanics::CreatureStats& stats = ptr.getClass().getCreatureStats(ptr);
            std::map<ESM::SummonKey, int>& creatureMap = stats.getSummonedCreatureMap();
            for (const auto& creature : creatureMap)
                cleanupSummonedCreature(stats, creature.second);
            creatureMap.clear();

            const ESM::Static* fx = MWBase::Environment::get().getWorld()->getStore().get<ESM::Static>()
                    .search("VFX_Summon_End");
            if (fx && !fx->mModel.empty())
                MWBase::Environment::get().getWorld()->spawnEffect("meshes\\" + fx->mModel,
                    "", despawnPosition);

            // X002: Send ObjectDelete last. Do not dereference ptr afterwards.
            mwmp::ObjectList *objectList = mwmp::Main::get().getNetworking()->getObjectList();
            objectList->reset();
            objectList->packetOrigin = mwmp::CLIENT_GAMEPLAY;
            objectList->addObjectGeneric(ptr);
            objectList->sendObjectDelete();
        /*
            End of tes3mp change (major)
        */
        }
'''
    fn = fn[:if_start] + replacement + fn[else_pos:]
    text = text[:fn_start] + fn + text[fn_end:]

save(rel, text)


# 3) Spell hit VFX and teleport animation lifetime guards.
rel = 'apps/openmw/mwmechanics/spellcasting.cpp'
text = load(rel)
old = '''                    const ESM::Static* castStatic;
                    if (!magicEffect->mHit.empty())
                        castStatic = MWBase::Environment::get().getWorld()->getStore().get<ESM::Static>().find (magicEffect->mHit);
                    else
                        castStatic = MWBase::Environment::get().getWorld()->getStore().get<ESM::Static>().find ("VFX_DefaultHit");
                    bool loop = (magicEffect->mData.mFlags & ESM::MagicEffect::ContinuousVfx) != 0;
                    // Note: in case of non actor, a free effect should be fine as well
                    MWRender::Animation* anim = MWBase::Environment::get().getWorld()->getAnimation(target);
                    if (anim && !castStatic->mModel.empty())
                        anim->addEffect("meshes\\\\" + castStatic->mModel, magicEffect->mIndex, loop, "", magicEffect->mParticle);
'''
new = '''                    const ESM::Static* castStatic = nullptr;
                    if (!magicEffect->mHit.empty())
                        castStatic = MWBase::Environment::get().getWorld()->getStore().get<ESM::Static>().search(magicEffect->mHit);
                    else
                        castStatic = MWBase::Environment::get().getWorld()->getStore().get<ESM::Static>().search("VFX_DefaultHit");
                    bool loop = (magicEffect->mData.mFlags & ESM::MagicEffect::ContinuousVfx) != 0;
                    // Missing/modded VFX is non-fatal: skip the visual only.
                    MWRender::Animation* anim = MWBase::Environment::get().getWorld()->getAnimation(target);
                    if (anim && castStatic && !castStatic->mModel.empty())
                        anim->addEffect("meshes\\\\" + castStatic->mModel, magicEffect->mIndex, loop, "", magicEffect->mParticle);
'''
text = replace_once(text, old, new, 'spellcasting.cpp hit VFX guard')
text = replace_once(
    text,
    '            MWRender::Animation* anim = MWBase::Environment::get().getWorld()->getAnimation(mCaster);\n',
    '            MWRender::Animation* anim = MWBase::Environment::get().getWorld()->getAnimation(target);\n',
    'spellcasting.cpp teleport initial animation target',
)
old = '''                MWBase::Environment::get().getWorld()->teleportToClosestMarker(target, marker);
                anim->removeEffect(effectId);
                const ESM::Static* fx = MWBase::Environment::get().getWorld()->getStore().get<ESM::Static>()
                    .search("VFX_Summon_end");
                if (fx)
                    anim->addEffect("meshes\\\\" + fx->mModel, -1);
'''
new = '''                MWBase::Environment::get().getWorld()->teleportToClosestMarker(target, marker);
                anim = MWBase::Environment::get().getWorld()->getAnimation(target);
                if (anim)
                {
                    anim->removeEffect(effectId);
                    const ESM::Static* fx = MWBase::Environment::get().getWorld()->getStore().get<ESM::Static>()
                        .search("VFX_Summon_end");
                    if (fx)
                        anim->addEffect("meshes\\\\" + fx->mModel, -1);
                }
'''
text = replace_once(text, old, new, 'spellcasting.cpp intervention animation lifetime')
old = '''                    action.execute(target);
                    anim->removeEffect(effectId);
'''
new = '''                    action.execute(target);
                    anim = MWBase::Environment::get().getWorld()->getAnimation(target);
                    if (anim)
                        anim->removeEffect(effectId);
'''
text = replace_once(text, old, new, 'spellcasting.cpp recall animation lifetime')
save(rel, text)


# 4) Attached VFX scene graph: null and exception safety.
rel = 'apps/openmw/mwrender/animation.cpp'
text = load(rel)
text = replace_once(
    text,
    '''    void Animation::addSpellCastGlow(const ESM::MagicEffect *effect, float glowDuration)\n    {\n        osg::Vec4f glowColor(1,1,1,1);\n''',
    '''    void Animation::addSpellCastGlow(const ESM::MagicEffect *effect, float glowDuration)\n    {\n        if (!effect || !mObjectRoot)\n            return;\n\n        osg::Vec4f glowColor(1,1,1,1);\n''',
    'animation.cpp spell glow root guard',
)
text = replace_once(
    text,
    '''    void Animation::addEffect (const std::string& model, int effectId, bool loop, const std::string& bonename, const std::string& texture)\n    {\n        if (!mObjectRoot.get())\n            return;\n''',
    '''    void Animation::addEffect (const std::string& model, int effectId, bool loop, const std::string& bonename, const std::string& texture)\n    {\n        if (!mObjectRoot || !mInsert || mPtr.isEmpty() || model.empty())\n            return;\n''',
    'animation.cpp addEffect root/ptr guard',
)
text = replace_once(
    text,
    '''            NodeMap::const_iterator found = getNodeMap().find(Misc::StringUtils::lowerCase(bonename));\n            if (found == getNodeMap().end())\n                throw std::runtime_error("Can't find bone " + bonename);\n\n            parentNode = found->second;\n''',
    '''            NodeMap::const_iterator found = getNodeMap().find(Misc::StringUtils::lowerCase(bonename));\n            if (found == getNodeMap().end() || !found->second)\n            {\n                Log(Debug::Warning) << "Skipping VFX '" << model << "': missing bone " << bonename;\n                return;\n            }\n\n            parentNode = found->second;\n''',
    'animation.cpp missing VFX bone',
)
text = replace_once(
    text,
    '''        parentNode->addChild(trans);\n        osg::ref_ptr<osg::Node> node = mResourceSystem->getSceneManager()->getInstance(model, trans);\n        node->getOrCreateStateSet()->setMode(GL_LIGHTING, osg::StateAttribute::OFF);\n''',
    '''        parentNode->addChild(trans);\n        osg::ref_ptr<osg::Node> node;\n        try\n        {\n            node = mResourceSystem->getSceneManager()->getInstance(model, trans);\n        }\n        catch (const std::exception& e)\n        {\n            parentNode->removeChild(trans);\n            Log(Debug::Warning) << "Skipping VFX '" << model << "': " << e.what();\n            return;\n        }\n        if (!node)\n        {\n            parentNode->removeChild(trans);\n            Log(Debug::Warning) << "Skipping VFX '" << model << "': scene instance is null";\n            return;\n        }\n        node->getOrCreateStateSet()->setMode(GL_LIGHTING, osg::StateAttribute::OFF);\n''',
    'animation.cpp VFX instance exception guard',
)
text = replace_once(
    text,
    '''    void Animation::removeEffect(int effectId)\n    {\n        RemoveCallbackVisitor visitor(effectId);\n''',
    '''    void Animation::removeEffect(int effectId)\n    {\n        if (!mInsert)\n        {\n            mHasMagicEffects = false;\n            return;\n        }\n        RemoveCallbackVisitor visitor(effectId);\n''',
    'animation.cpp removeEffect insert guard',
)
text = replace_once(
    text,
    '''    void Animation::getLoopingEffects(std::vector<int> &out) const\n    {\n        if (!mHasMagicEffects)\n            return;\n''',
    '''    void Animation::getLoopingEffects(std::vector<int> &out) const\n    {\n        if (!mHasMagicEffects || !mInsert)\n            return;\n''',
    'animation.cpp getLoopingEffects insert guard',
)
text = replace_once(
    text,
    '''    void Animation::updateEffects()\n    {\n        // We do not need to visit scene every frame.\n        // We can use a bool flag to check in spellcasting effect found.\n        if (!mHasMagicEffects)\n            return;\n''',
    '''    void Animation::updateEffects()\n    {\n        // We do not need to visit scene every frame.\n        // We can use a bool flag to check in spellcasting effect found.\n        if (!mHasMagicEffects || !mInsert)\n            return;\n''',
    'animation.cpp updateEffects insert guard',
)
save(rel, text)


# 5) Free/world VFX exception safety (summon-end, explosions, impact effects).
rel = 'apps/openmw/mwrender/effectmanager.cpp'
text = load(rel)
text = replace_once(
    text,
    '#include \"effectmanager.hpp\"\n\n',
    '#include \"effectmanager.hpp\"\n\n#include <exception>\n',
    'effectmanager.cpp exception include',
)
text = replace_once(
    text,
    '#include <components/resource/scenemanager.hpp>\n',
    '#include <components/resource/scenemanager.hpp>\n#include <components/debug/debuglog.hpp>\n',
    'effectmanager.cpp debug include',
)
old = '''void EffectManager::addEffect(const std::string &model, const std::string& textureOverride, const osg::Vec3f &worldPosition, float scale, bool isMagicVFX)\n{\n    osg::ref_ptr<osg::Node> node = mResourceSystem->getSceneManager()->getInstance(model);\n\n    node->setNodeMask(Mask_Effect);\n'''
new = '''void EffectManager::addEffect(const std::string &model, const std::string& textureOverride, const osg::Vec3f &worldPosition, float scale, bool isMagicVFX)\n{\n    if (model.empty() || !mParentNode || !mResourceSystem)\n        return;\n\n    osg::ref_ptr<osg::Node> node;\n    try\n    {\n        node = mResourceSystem->getSceneManager()->getInstance(model);\n    }\n    catch (const std::exception& e)\n    {\n        Log(Debug::Warning) << "Skipping free VFX '" << model << "': " << e.what();\n        return;\n    }\n    if (!node)\n    {\n        Log(Debug::Warning) << "Skipping free VFX '" << model << "': scene instance is null";\n        return;\n    }\n\n    node->setNodeMask(Mask_Effect);\n'''
text = replace_once(text, old, new, 'effectmanager.cpp safe addEffect')
save(rel, text)

print('ArenaMP Android magic/Mali stability patch X002 applied')
