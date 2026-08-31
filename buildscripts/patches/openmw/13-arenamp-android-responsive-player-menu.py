#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit('usage: 13-arenamp-android-responsive-player-menu.py <ArenaMP source dir>')
root = Path(sys.argv[1]).resolve()
path = root / 'apps/openmw/mwmp/GUI/GUIChat.cpp'
if not path.is_file():
    raise SystemExit('missing GUIChat.cpp')
text = path.read_text(encoding='utf-8')

helper = '''    // Android: keep the desktop 20x2 palette on wide viewports, but\n    // adapt the number of columns on narrow logical screens.\n    int colorGridColumnsForWidth(int innerWidth)\n    {\n        constexpr int gridGap = 4;\n        const int available = std::max(1, innerWidth - 12);\n        return std::max(5, std::min(20, (available + gridGap) / (sMinimumButtonWidth + gridGap)));\n    }\n\n    int colorDrawerHeightForWidth(int innerWidth)\n    {\n        constexpr int gridGap = 4;\n        constexpr int gridTop = 28;\n        constexpr int rowHeight = 26;\n        constexpr int colorCount = 40;\n        const int columns = colorGridColumnsForWidth(innerWidth);\n        const int rows = (colorCount + columns - 1) / columns;\n        const int required = gridTop + rows * rowHeight + std::max(0, rows - 1) * gridGap + 6;\n        return std::max(sColorDrawerHeight, required);\n    }\n\n'''
if 'int colorGridColumnsForWidth(int innerWidth)' not in text:
    anchor = '    constexpr int sColorDrawerHeight = 96;\n'
    if text.count(anchor) != 1:
        raise SystemExit(f'GUIChat constants anchor matched {text.count(anchor)} times')
    text = text.replace(anchor, anchor + helper, 1)

old = '''        // 40 swatches over two rows of 20.\n        mColorBar->setCoord(sSideMargin, sDrawerTop, inner, sColorDrawerHeight);\n        mColorBarLabel->setCoord(8, 4, inner - 16, 20);\n        std::vector<MyGUI::Widget*> swatches;\n        for (int i = 0; i < sColorSlotCount; ++i)\n            swatches.push_back(mColorButtons[i]);\n        layoutGrid(swatches, 6, 28, inner - 12, 26, 20, 4);\n'''
new = '''        // Android: 20x2 on wide layouts, adaptive rows on narrow viewports so\n        // all 40 colours remain reachable instead of being clipped off-screen.\n        const int colorColumns = colorGridColumnsForWidth(inner);\n        const int colorDrawerHeight = colorDrawerHeightForWidth(inner);\n        mColorBar->setCoord(sSideMargin, sDrawerTop, inner, colorDrawerHeight);\n        mColorBarLabel->setCoord(8, 4, inner - 16, 20);\n        std::vector<MyGUI::Widget*> swatches;\n        for (int i = 0; i < sColorSlotCount; ++i)\n            swatches.push_back(mColorButtons[i]);\n        layoutGrid(swatches, 6, 28, inner - 12, 26, colorColumns, 4);\n'''
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit('GUIChat colour-grid anchor not found or upstream shape changed')

path.write_text(text, encoding='utf-8')
print('ArenaMP Android responsive Player Menu patch applied')
