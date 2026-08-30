# Stegered Client

Stegered Client is a private Minecraft Fabric client based on the Achilles codebase. Built JARs are available to authorized repository members through private GitHub Releases.

## Credits
- **sootysplash** - 1133 commits
- **NoboKik** - 464 commits
- **pycat** - 199 commits
- **deadLORD135** - 25 commits
- **lagoon** - 19 commits
- **miep** - 12 commits
- **Cecilia** - 5 commits
- **LvStrng** - 2 commits
- **Snrios** - 1 commit
- **Violet** - 1 commit

## License
This project is subject to the **GNU General Public License v3.0**.

Actions that you are allowed to do:
- **Use**
- **Share**
- **Modify**

If you use ANY code from the source:<br>
**You must disclose source code when distributing the software or a modified version. Your modified versions must also be licensed under GPL v3.**
**Your modified application must also be licensed under the GPL**

## Fork changes
- Added **Echo Auto Anchor** under **Crystal**, ported from ECHO b0.0.6 with its full bypass suite: optimal anchor placement near your current target (exposure-scored over the target's feet region), a **RotationConvergenceTracker** that only fires interactions on ticks where the per-tick rotation delta is under Grim's `deltaX > 2` DuplicateRotPlace gate, **AnchorAimSearch** (raycast-clean aim points even when adjacent blocks occlude the eye-side face), strict vs loose placement raycasts (`Can Place Legit`), InteractionResult-consumption gating with vanilla place cooldowns, randomized charge/explode delays, optional safety glowstone between you and the anchor (with damage threshold + timeout), silent aim through the rotation manager with movement remap, a **SwapStateManager** for silent slot swaps with scheduled restoration, a same-use-key mode that cancels vanilla use packets so they are never duplicated, and a live render of the best placement position. Sibling anchor modules are disabled automatically while it runs.
- Added the **ECHO Glass UI** as the default ClickGUI style: compact dark/light glass category panels, blue accent rows, responsive scrolling, module key labels, expandable settings, and the Features/Configs/Legit navigation dock. Panels automatically wrap into a resolution-aware grid so every category remains fully on-screen.
- Added **ECHO Auto Crystal** under **Crystal**, ported from ECHO b0.0.6. It includes crosshair-driven crystal place/break timing plus the integrated **Place Obsidian** sequence, activation item picker, switch-back, confirmation, fail-chance, and input-simulation settings.
- Fixed the startup crash caused by calling `ImGui.render()` before an ImGui frame had begun. Rendering now waits for complete GLFW/font initialization and guarantees balanced frame finalization.
- Added **Smart Auto Anchor** under **Crystal**. Place an anchor and keep right click held: it switches to glowstone and charges after two ticks by default, then starts POV detection. Looking down/behind selects safe mode and places a glowstone block where you aim; normal mode proceeds directly to detonation. Totems are preferred when available but are not required. **Strict** server timing is enabled by default and prevents same-tick slot/action bursts; **Fast** retains zero-delay coalescing for environments that permit it. Successful cycles leave the chosen detonation slot selected.
- Added the **Prevent** module under **Misc**, including configurable protections for glowstone, anchors, obsidian punching, and ender-chest interactions.
- Removed the external login/HWID authentication flow. Stegered Client initializes its modules, configuration, event handlers, and GUI directly at startup.

## Compiling from source
Install Java 21, clone this repository, and run `./gradlew build`. The Gradle wrapper is included, and the built JAR is written to the **client/build/libs** directory.

The GitHub Actions workflow builds every push and pull request and uploads a downloadable JAR artifact. **Every push to `main` whose `client_version` in `gradle.properties` has not been released yet automatically publishes a GitHub Release** (`v<client_version>`) containing the remapped JAR and its SHA-256 checksum — just bump `client_version` and push. Pushing a tag matching `v<client_version>` also publishes the release, and manual runs can be triggered from the Actions tab.

## Discord
Join our discord: https://discord.gg/EW6tWKJxh7. You may report bugs there, ask for support and chat with the community. We may release updates for the client in the future.

## Supporting us
Make sure to star the repository! That would mean a lot to us.

