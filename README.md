# TurtleBox GX

A native Android rebuild of TurtleBox. Card layouts stay in an XML file and a zip in a folder on the phone,
so they can be edited without recompiling the app.

## Getting the app

Every push to `main` builds the APK on GitHub. On your phone:

1. Open the repo's **Releases** page and tap **Latest build**.
2. Download `TurtleBox-GX.apk` and install it.

Builds are signed with one fixed key, so each new build installs over the last one.
(The key is committed to this public repo on purpose; this is a sideloaded hobby app, not a store release.)

## Phases

1. Pipeline and loader (this is phase 1a: pick the layout folder, the app reads the XML and zip).
2. Editor menus from the XML, and opening and saving `.msoe-set` files.
3. Text engine port (line layout, condense, justify, kerning).
4. Text markup and weight.
5. Extras.
