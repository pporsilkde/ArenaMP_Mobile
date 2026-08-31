#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit('usage: 10-arenamp-auth-map-inventory-stability.py <AMP source dir>')

root = Path(sys.argv[1]).resolve()


def load(rel: str) -> str:
    path = root / rel
    if not path.is_file():
        raise SystemExit(f'missing required source file: {rel}')
    return path.read_text(encoding='utf-8')


def save(rel: str, text: str) -> None:
    (root / rel).write_text(text, encoding='utf-8')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one anchor, found {count}')
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# 1) Account login: ignore a second Enter/click while the first reply is being
#    consumed.  The modal is destroyed by GUIController after eventDone().
# ---------------------------------------------------------------------------
rel = 'apps/openmw/mwmp/GUI/GUILogin.hpp'
text = load(rel)
text = replace_once(
    text,
    '        bool mRetryMode = false;\n        bool mLoginEditable = true;\n',
    '        bool mRetryMode = false;\n        bool mLoginEditable = true;\n        bool mSubmitted = false;\n',
    'GUILogin.hpp submit guard',
)
save(rel, text)

rel = 'apps/openmw/mwmp/GUI/GUILogin.cpp'
text = load(rel)
text = replace_once(
    text,
    '    void GUILogin::onConnect(MyGUI::Widget*)\n    {\n        MWBase::WindowManager* windowManager = MWBase::Environment::get().getWindowManager();\n',
    '    void GUILogin::onConnect(MyGUI::Widget*)\n    {\n        // A fast Enter + button click can otherwise emit eventDone twice before\n        // GUIController has removed this modal.  That produces two password\n        // replies and makes the auth -> world transition timing dependent.\n        if (mSubmitted)\n            return;\n\n        MWBase::WindowManager* windowManager = MWBase::Environment::get().getWindowManager();\n',
    'GUILogin.cpp early submit guard',
)
text = replace_once(
    text,
    '        applyLanguage(getLanguage(), true);\n        eventDone(this);\n',
    '        applyLanguage(getLanguage(), true);\n        mSubmitted = true;\n        if (mConnect)\n            mConnect->setEnabled(false);\n        eventDone(this);\n',
    'GUILogin.cpp latch submit',
)
save(rel, text)


# ---------------------------------------------------------------------------
# 2) Keep the pre-auth handshake minimal.  Main already sends PlayerBaseInfo
#    once during the initial handshake.  Re-sending it immediately before the
#    password response can race remote-player creation / appearance refresh.
# ---------------------------------------------------------------------------
rel = 'apps/openmw/mwmp/GUIController.cpp'
text = load(rel)
old = '''    // The account card is shown after the initial PlayerBaseInfo handshake, so
    // re-send the selected RU/EN flag before the password response. The server
    // can then localise registration/login result messages for this player.
    LocalPlayer* localPlayer = Main::get().getLocalPlayer();
    localPlayer->updateLanguage();
    PlayerPacket* baseInfoPacket = Main::get().getNetworking()->getPlayerPacket(ID_PLAYER_BASEINFO);
    baseInfoPacket->setPlayer(localPlayer);
    baseInfoPacket->Send();

'''
new = '''    // Main::updateWorld() already sent the initial BaseInfo handshake.  Keep the
    // selected language locally up to date, but do not send a second BaseInfo
    // adjacent to the password packet: repeated appearance/base-info processing
    // during authentication was a timing-sensitive remote-player crash source.
    Main::get().getLocalPlayer()->updateLanguage();

'''
text = replace_once(text, old, new, 'GUIController.cpp duplicate BaseInfo')
save(rel, text)


# ---------------------------------------------------------------------------
# 3) Do not run normal gameplay/cell synchronization until authentication (or
#    registration + CharGen) is complete.  The initial BaseInfo/Loaded handshake
#    remains available so the server can open the password dialog.
# ---------------------------------------------------------------------------
rel = 'apps/openmw/mwmp/Main.cpp'
text = load(rel)
text = replace_once(
    text,
    '        mNetworking->getPlayerPacket(ID_PLAYER_BASEINFO)->Send();\n        mNetworking->getPlayerPacket(ID_LOADED)->Send();\n        mLocalPlayer->updateStatsDynamic(true);\n        get().getGUIController()->setChatVisible(true);\n',
    '        mNetworking->getPlayerPacket(ID_PLAYER_BASEINFO)->Send();\n        mNetworking->getPlayerPacket(ID_LOADED)->Send();\n        // Do not send normal stat/cell/gameplay synchronization until the\n        // server has completed login (or registration + CharGen).\n        get().getGUIController()->setChatVisible(true);\n',
    'Main.cpp pre-auth forced stats',
)
text = replace_once(
    text,
    '    else\n    {\n        mLocalPlayer->update();\n        mCellController->updateLocal(false);\n',
    '    else\n    {\n        // Password/registration dialogs are part of the network handshake, not\n        // the live world.  Sending movement, equipment, stats and cell actor\n        // updates here used to overlap the auth result and occasionally race\n        // remote-player/world creation.\n        if (!mLocalPlayer->isLoggedIn())\n            return;\n\n        mLocalPlayer->update();\n        mCellController->updateLocal(false);\n',
    'Main.cpp auth gameplay gate',
)
save(rel, text)


# ---------------------------------------------------------------------------
# 4) Remote player: tolerate the short interval where a player object exists in
#    PlayerList but its world Ptr is not yet usable, and never reload an orphaned
#    renderer reference.  These are defensive guards around auth/cell races.
# ---------------------------------------------------------------------------
rel = 'apps/openmw/mwmp/DedicatedPlayer.cpp'
text = load(rel)
text = replace_once(
    text,
    'void DedicatedPlayer::update(float dt)\n{\n    // Only move and set anim flags if the framerate isn\'t too low\n',
    'void DedicatedPlayer::update(float dt)\n{\n    // Network packets can create/remove a DedicatedPlayer in the same frame as\n    // PlayerList::update().  Never dereference a transient/disabled world Ptr.\n    if (!reference || ptr.isEmpty() || !ptr.isInCell())\n        return;\n\n    // Only move and set anim flags if the framerate isn\'t too low\n',
    'DedicatedPlayer.cpp update Ptr guard',
)
text = replace_once(
    text,
    '        if (!ptr.isEmpty() && rendererIdentityChanged)\n',
    '        if (!ptr.isEmpty() && ptr.isInCell() && rendererIdentityChanged)\n',
    'DedicatedPlayer.cpp appearance reload guard',
)
text = replace_once(
    text,
    'void DedicatedPlayer::reloadPtr()\n{\n    MWBase::World *world = MWBase::Environment::get().getWorld();\n    world->disable(ptr);\n    world->enable(ptr);\n}\n',
    'void DedicatedPlayer::reloadPtr()\n{\n    if (!reference || ptr.isEmpty() || !ptr.isInCell())\n        return;\n\n    MWBase::World *world = MWBase::Environment::get().getWorld();\n    world->disable(ptr);\n    world->enable(ptr);\n}\n',
    'DedicatedPlayer.cpp reload Ptr guard',
)
save(rel, text)


# ---------------------------------------------------------------------------
# 5) Large inventory local map: an unavailable render texture is asynchronous,
#    not a permanent failure.  Do not cache an empty OSGTexture sentinel because
#    that prevents subsequent frames from ever asking for the real texture.
# ---------------------------------------------------------------------------
rel = 'apps/openmw/mwgui/mapwindow.cpp'
text = load(rel)
text = replace_once(
    text,
    '''                else
                    entry.mMapTexture.reset(new osgMyGUI::OSGTexture("", nullptr));
''',
    '''                // If the renderer has not produced the texture yet, keep
                // mMapTexture null. updateRequiredMaps() will retry next frame.
''',
    'mapwindow.cpp local map retry',
)
text = replace_once(
    text,
    '''                else
                {
                    entry.mFogWidget->setImageTexture("black");
                    entry.mFogTexture.reset(new osgMyGUI::OSGTexture("", nullptr));
                }
''',
    '''                else
                {
                    // Black is only a temporary placeholder.  Keep mFogTexture
                    // null so the actual fog render texture can replace it later.
                    entry.mFogWidget->setImageTexture("black");
                }
''',
    'mapwindow.cpp fog retry',
)
save(rel, text)


# ---------------------------------------------------------------------------
# 6) Inventory defaults: paper doll on; icon/grid mode by default.  The view
#    toggle persists the user's later choice instead of resetting to list mode.
# ---------------------------------------------------------------------------
rel = 'apps/openmw/mwgui/inventorywindow.cpp'
text = load(rel)
text = replace_once(
    text,
    '''        // the paper doll natively; expose it as an optional layout pane instead
        // of permanently sacrificing half of the table.  Hidden is the default
        // to match the original mod, and the choice persists in settings.
''',
    '''        // the paper doll natively; expose it as an optional layout pane instead
        // of permanently sacrificing half of the table.  ArenaMP enables it by
        // default, while preserving an explicit user choice in settings.
''',
    'inventorywindow.cpp paper doll comment',
)
text = replace_once(
    text,
    '''        getWidget(mItemView, "ItemView");
        mItemView->setExtendedMode(true);
        mItemView->setInternalViewModeButtonVisible(false);
''',
    '''        getWidget(mItemView, "ItemView");
        mItemView->setExtendedMode(true);
        mItemView->setViewMode(Settings::Manager::getBool("inventory grid view", "GUI")
            ? ItemView::View_Grid : ItemView::View_List);
        mItemView->setInternalViewModeButtonVisible(false);
''',
    'inventorywindow.cpp initial grid mode',
)
text = replace_once(
    text,
    '''        mItemView->setViewMode(nextMode);
        updateBottomControls();
''',
    '''        mItemView->setViewMode(nextMode);
        Settings::Manager::setBool("inventory grid view", "GUI", nextMode == ItemView::View_Grid);
        updateBottomControls();
''',
    'inventorywindow.cpp persist grid mode',
)
save(rel, text)


# Default settings live in the full AMP checkout (apps.zip supplied for local
# validation contains only apps/, so tolerate a missing file in that snapshot).
settings_rel = 'files/settings-default.cfg'
settings_path = root / settings_rel
if settings_path.is_file():
    text = settings_path.read_text(encoding='utf-8')
    if 'inventory paper doll = false' in text:
        text = text.replace('inventory paper doll = false', 'inventory paper doll = true', 1)
    elif 'inventory paper doll = true' not in text:
        gui = '[GUI]\n'
        if gui not in text:
            raise SystemExit('settings-default.cfg: [GUI] section not found')
        text = text.replace(gui, gui + 'inventory paper doll = true\n', 1)

    if 'inventory grid view =' not in text:
        anchor = 'inventory paper doll = true\n'
        if anchor not in text:
            raise SystemExit('settings-default.cfg: paper doll anchor not found')
        text = text.replace(anchor, anchor + 'inventory grid view = true\n', 1)
    else:
        lines = []
        for line in text.splitlines(True):
            if line.strip().startswith('inventory grid view ='):
                suffix = '\n' if line.endswith('\n') else ''
                line = 'inventory grid view = true' + suffix
            lines.append(line)
        text = ''.join(lines)
    settings_path.write_text(text, encoding='utf-8')

print('ArenaMP auth/map/inventory stability patch applied')
