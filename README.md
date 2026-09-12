# OneWorldRollback

Client-side Fabric 1.21.11 mod by SmilerRyan for keeping one manual world backup.

## Controls

- **Backup** — saves/replaces the current world checkpoint.
- **Rollback** — restores the saved checkpoint.
- Both bindings are **unbound by default** and can be assigned to keyboard keys or mouse buttons in Minecraft's Options → Controls.

The checkpoint is stored inside the world at:

```text
saves/<world>/checkpoint/
```

A checkpoint is literally a copy of the whole world folder into a folder called checkpoint in the world's folder.

Only one checkpoint can exist per world at a time.

Saving a new checkpoint replaces the old one only after the new copy has finished successfully.
Restoring a checkpoint never deletes it.

When a checkpoint exists, the death screen shows its age, for example:

You can delete the checkpoint folder if you do not want to keep the checkpoint.

If you want to manually restore a world without the mod:
 - Delete every file except for the checkpoint folder
 - Move all the files out of the checkpoint folder
 - Delete the empty checkpoint folder

```text
Roll back to 2h 14m 37s ago
```

Zero-valued time units are omitted.


Rollback avoids the normal title/disconnect screen. The existing world view is kept as long as Minecraft allows while the server is stopped and the files are replaced, then the world is reopened normally.


Rollback uses a temporary in-game transition screen while Minecraft stops and restarts the integrated server, so the normal Saving World and Loading Terrain screens are covered.
