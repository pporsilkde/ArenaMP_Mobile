# ArenaMP Mobile X057a — patch-pipeline fix

X057a replaces the X057 file-overlay approach with normal build-time patches.

Build order for fetched AMP source:

1. `06-arenamp-mobile-cumulative-v1-2.patch`
2. network identity Python step 07
3. `08-arenamp-android-compact-display-v1-2-7.patch`
4. Android steps 09–12
5. `13-arenamp-x056-server-network.patch`
6. `14-arenamp-x056-client-player-menu.patch`
7. `15-arenamp-x056-resources.patch`

No `x056-android-overlay/` and no `13-sync-x056-android.py` are used.
The three new patches are generated against the exact post-12 Android source state and cover 142 X056 parity files in disjoint groups (57 server/network, 74 client, 11 resources).

Android on-screen Y now holds real `KEYCODE_Y` from touch down to touch up. The C++ `player menu hold` logic therefore sees the same short/long press semantics as desktop.
