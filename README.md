# AFK Rollback

Client-side Fabric 1.21.11 mod for keeping one manual world checkpoint.

## Controls

- **Shift+P** — Save/replace the checkpoint (default).
- **P** — Restore the checkpoint (default).
- Both key bindings can be changed in Minecraft's **Options → Controls → Key Binds**.

The checkpoint is stored inside the world at:

```text
saves/<world>/checkpoint/
```

Only one checkpoint exists at a time. Saving a new checkpoint replaces the old one only after the new copy has finished successfully. Restoring a checkpoint never deletes the checkpoint; it remains available until the next explicit save.

The death screen shows **Last checkpoint** when a valid checkpoint exists.
