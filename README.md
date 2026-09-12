AFK Rollback v1.1.0

Rollback now waits for the integrated server to fully shut down and for Minecraft to release the world before replacing the world folder. This avoids level.dat save/rename races during rollback.

# AFK Rollback

A tiny client-side Fabric mod for Minecraft 1.21.11 that keeps one rolling world snapshot whenever you stop moving for a configurable amount of time.

## What it does

- Default AFK delay: **10 seconds**.
- Works in **singleplayer worlds**.
- When you have not moved for the configured delay, the current world is saved and copied to:
  `saves/<world-name>-afk`
- Only one snapshot is kept for each world. The next AFK checkpoint replaces it.
- Moving again starts a new idle period, but does **not** immediately overwrite the checkpoint.
- If you die and an AFK snapshot exists, the death screen gets a **Rollback to AFK Snapshot** button.
- Clicking it safely disconnects from the world, replaces the live world with the AFK copy, and automatically loads the restored world.
- The `session.lock` file is deliberately not copied.
- There is no automatic rollback and no KeepInventory behavior.

This means you can treat the snapshot as a single rollback point rather than a collection of lives.

## Configuration

After first launch, edit:

`config/afk-rollback.properties`

Example:

```properties
afk_seconds=10
```

The value is clamped to 1–3600 seconds.

## Build

The project targets Minecraft **1.21.11**, Java 21, Fabric Loader 0.18.4, Fabric API 0.141.3+1.21.11, and Fabric Loom 1.14. Fabric documents Loom as the Gradle toolchain used for Fabric mods, and 1.21.11 is the last obfuscated Minecraft release, so this project uses the remapping Loom plugin and official Mojang mappings.

### Windows

Run:

```bat
build.bat
```

### Gradle directly

```bat
gradlew.bat build
```

The finished mod is in `build/libs/`.

## GitHub Actions

Push the repository to GitHub. The included workflow builds the mod on every push and pull request and uploads the built JAR as a workflow artifact.
