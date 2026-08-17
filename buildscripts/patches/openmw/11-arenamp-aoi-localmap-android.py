#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit('usage: 11-arenamp-aoi-localmap-android.py <AMP source dir>')

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
# 1) Player AOI bookkeeping.
#    This script is deliberately idempotent because AMP/main may already carry
#    these fixes. The Android builder can therefore track main without a fragile
#    second git-apply of the same source diff.
# ---------------------------------------------------------------------------
rel = 'apps/openmw-mp/Player.hpp'
text = load(rel)
if '#include <set>' not in text:
    text = replace_once(text, '#include <map>\n', '#include <map>\n#include <set>\n', 'Player.hpp <set>')

if 'getLoadedPlayerGuids() const;' not in text:
    anchor = '    void sendToLoaded(mwmp::PlayerPacket *myPacket);\n'
    addition = anchor + '''\n    // Snapshot the current AOI recipients. This is also used to remember\n    // players that were sharing a cell immediately before a CellState unload.\n    std::set<RakNet::RakNetGUID> getLoadedPlayerGuids() const;\n    void queueCellChangeRecipient(RakNet::RakNetGUID guid);\n    void sendToQueuedCellChangeRecipients(mwmp::PlayerPacket *myPacket);\n'''
    text = replace_once(text, anchor, addition, 'Player.hpp AOI methods')

if 'pendingCellChangeRecipients;' not in text:
    anchor = '    bool appearanceAuthoritative;\n'
    addition = anchor + '''\n    // A PlayerCellState packet can remove the old-cell observers before the\n    // following PlayerCellChange packet is processed. Keep those departed AOI\n    // recipients long enough to send them the cell change that despawns the\n    // remote representation from their old active cell.\n    std::set<RakNet::RakNetGUID> pendingCellChangeRecipients;\n'''
    text = replace_once(text, anchor, addition, 'Player.hpp AOI pending recipients')
save(rel, text)


rel = 'apps/openmw-mp/Player.cpp'
text = load(rel)
if 'Player::getLoadedPlayerGuids() const' not in text:
    marker = 'void Player::forEachLoaded(std::function<void(Player *pl, Player *other)> func)\n'
    if text.count(marker) != 1:
        raise SystemExit(f'Player.cpp AOI insertion: expected one forEachLoaded marker, found {text.count(marker)}')
    implementation = r'''std::set<RakNet::RakNetGUID> Player::getLoadedPlayerGuids() const
{
    std::set<RakNet::RakNetGUID> result;

    for (auto cell : cells)
    {
        if (cell == nullptr)
            continue;

        for (auto pl : *cell)
        {
            if (pl == nullptr || pl == this || !pl->isVisibleToOthers())
                continue;
            result.insert(pl->guid);
        }
    }

    return result;
}

void Player::queueCellChangeRecipient(RakNet::RakNetGUID guid)
{
    if (guid == this->guid || guid == RakNet::UNASSIGNED_CRABNET_GUID)
        return;
    pendingCellChangeRecipients.insert(guid);
}

void Player::sendToQueuedCellChangeRecipients(mwmp::PlayerPacket *myPacket)
{
    if (pendingCellChangeRecipients.empty())
        return;

    // If AOI overlap was restored before the CellChange arrived, sendToLoaded()
    // has already covered that peer and we should not send a duplicate.
    const std::set<RakNet::RakNetGUID> currentRecipients = getLoadedPlayerGuids();

    for (const RakNet::RakNetGUID& recipientGuid : pendingCellChangeRecipients)
    {
        if (currentRecipients.count(recipientGuid) != 0)
            continue;

        Player* recipient = Players::getPlayer(recipientGuid);
        if (recipient == nullptr || !recipient->isVisibleToOthers())
            continue;

        myPacket->setPlayer(this);
        myPacket->Send(recipientGuid);
    }

    pendingCellChangeRecipients.clear();
}

'''
    text = text.replace(marker, implementation + marker, 1)
save(rel, text)


# ---------------------------------------------------------------------------
# 2) CellChange: also notify observers dropped from the old AOI by CellState.
# ---------------------------------------------------------------------------
rel = 'apps/openmw-mp/processors/player/ProcessorPlayerCellChange.hpp'
text = load(rel)
if 'sendToQueuedCellChangeRecipients(&packet);' not in text:
    anchor = '                player.sendToLoaded(&packet);\n'
    addition = anchor + '''\n                // CellState is processed first during a scene transition. It may\n                // have already removed players that remained in the previous room\n                // from this player's loaded-cell AOI. Send the same reliable cell\n                // change to those departed observers so their DedicatedPlayer is\n                // moved out of the old active scene instead of remaining at the door.\n                player.sendToQueuedCellChangeRecipients(&packet);\n'''
    text = replace_once(text, anchor, addition, 'ProcessorPlayerCellChange AOI send')
save(rel, text)


# ---------------------------------------------------------------------------
# 3) CellState: snapshot old AOI before update and defer actual cell-change
#    notification until the authoritative destination arrives.
#    Also initialize playerController: V1 source had the member but missed this.
# ---------------------------------------------------------------------------
rel = 'apps/openmw-mp/processors/player/ProcessorPlayerCellState.hpp'
text = load(rel)
controller_init = '            playerController = Networking::get().getPlayerPacketController();\n'
if controller_init not in text:
    text = replace_once(
        text,
        '            BPP_INIT(ID_PLAYER_CELL_STATE)\n',
        '            BPP_INIT(ID_PLAYER_CELL_STATE)\n' + controller_init,
        'ProcessorPlayerCellState playerController init',
    )

if 'recipientsBefore = player.getLoadedPlayerGuids();' not in text:
    update_line = '            CellController::get()->update(&player);\n'
    if text.count(update_line) != 1:
        raise SystemExit(f'ProcessorPlayerCellState update anchor: expected one, found {text.count(update_line)}')

    before = '''            // Remember who could see this player before the CellState update.\n            // Scene transitions send CellState before the subsequent CellChange;\n            // without this snapshot the old-room observers disappear from\n            // sendToLoaded() too early and never learn that the player left.\n            const std::set<RakNet::RakNetGUID> recipientsBefore = player.getLoadedPlayerGuids();\n\n            bool currentCellIsBeingUnloaded = false;\n            const std::string currentCellDescription = player.cell.getShortDescription();\n            if (!currentCellDescription.empty())\n            {\n                for (const auto& cellState : player.cellStateChanges)\n                {\n                    if (cellState.type == mwmp::CellState::UNLOAD\n                        && cellState.cell.getShortDescription() == currentCellDescription)\n                    {\n                        currentCellIsBeingUnloaded = true;\n                        break;\n                    }\n                }\n            }\n\n'''
    text = text.replace(update_line, before + update_line, 1)

    # Replace the old immediate snapshot exchange after CellController::update.
    update_pos = text.find(update_line)
    exchange_line = '            Networking::getPtr()->exchangePlayerSnapshots(&player);\n'
    exchange_pos = text.find(exchange_line, update_pos + len(update_line))
    if exchange_pos < 0:
        raise SystemExit('ProcessorPlayerCellState: post-update exchange anchor not found')

    # Keep the update itself, replace comments/blank lines through the old exchange.
    post_start = update_pos + len(update_line)
    post_end = exchange_pos + len(exchange_line)
    after = '''\n            const std::set<RakNet::RakNetGUID> recipientsAfter = player.getLoadedPlayerGuids();\n            PlayerPacket* cellChangePacket = playerController->GetPacket(ID_PLAYER_CELL_CHANGE);\n\n            for (const RakNet::RakNetGUID& recipientGuid : recipientsBefore)\n            {\n                if (recipientsAfter.count(recipientGuid) != 0)\n                    continue;\n\n                if (currentCellIsBeingUnloaded)\n                {\n                    // player.cell still describes the old room until CellChange.\n                    player.queueCellChangeRecipient(recipientGuid);\n                }\n                else\n                {\n                    // AOI can shrink while the authoritative player.cell stays valid.\n                    cellChangePacket->setPlayer(&player);\n                    cellChangePacket->Send(recipientGuid);\n                }\n            }\n\n            // Do not publish the old room to the newly loaded AOI while the\n            // authoritative destination CellChange is still in flight.\n            if (!currentCellIsBeingUnloaded)\n                Networking::getPtr()->exchangePlayerSnapshots(&player);\n'''
    text = text[:post_start] + after + text[post_end:]
save(rel, text)


# ---------------------------------------------------------------------------
# 4) Android local-map fog texture: avoid dynamic PBO uploads through NG-GL4ES.
# ---------------------------------------------------------------------------
rel = 'apps/openmw/mwrender/localmap.cpp'
text = load(rel)
pbo_call = '    mFogOfWarImage->setPixelBufferObject(new osg::PixelBufferObject);\n'
if pbo_call in text:
    call_pos = text.find(pbo_call)
    before_call = text[max(0, call_pos - 220):call_pos]
    if '#ifndef __ANDROID__' not in before_call:
        old = '    // Assign a PixelBufferObject for asynchronous transfer of data to the GPU\n' + pbo_call
        if old in text:
            new = '''    // Desktop benefits from asynchronous PBO uploads. On Android the renderer\n    // runs through NG-GL4ES, where the dynamic fog image's PBO path can leave the\n    // alpha texture permanently opaque. The large local-map window then becomes\n    // a black rectangle even though the underlying RTT (and HUD minimap) is fine.\n#ifndef __ANDROID__\n''' + pbo_call + '#endif\n'
            text = text.replace(old, new, 1)
        else:
            # Tolerate upstream comment changes: wrap the call itself.
            text = text.replace(pbo_call, '#ifndef __ANDROID__\n' + pbo_call + '#endif\n', 1)
save(rel, text)

print('ArenaMP AOI/local-map patch verified/applied (idempotent); CellState packet controller initialized')
