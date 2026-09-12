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

Only one checkpoint exists at a time. Saving a new checkpoint replaces the old one only after the new copy has finished successfully. Restoring a checkpoint never deletes it.

When a checkpoint exists, the death screen shows its age, for example:

```text
Roll back to 2h 14m 37s ago
```

Zero-valued time units are omitted.
