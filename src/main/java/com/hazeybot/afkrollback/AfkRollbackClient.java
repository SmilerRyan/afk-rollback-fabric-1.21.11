package com.hazeybot.afkrollback;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

public final class AfkRollbackClient implements ClientModInitializer {
    public static final String MOD_ID = "oneworldrollback";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final String CHECKPOINT_DIR = "checkpoint";
    private static final String CHECKPOINT_TEMP_DIR = "checkpoint.tmp";
    private static final String CHECKPOINT_TIMESTAMP_FILE = "checkpoint.timestamp";

    private static final KeyMapping.Category CHECKPOINT_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(MOD_ID, "checkpoint")
    );

    // Both bindings start unbound. They can be assigned to any supported input in Controls.
    private static final KeyMapping SAVE_CHECKPOINT_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                    "key.oneworldrollback.backup",
                    InputConstants.Type.KEYSYM,
                    InputConstants.UNKNOWN.getValue(),
                    CHECKPOINT_CATEGORY
            )
    );

    private static final KeyMapping LOAD_CHECKPOINT_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                    "key.oneworldrollback.rollback",
                    InputConstants.Type.KEYSYM,
                    InputConstants.UNKNOWN.getValue(),
                    CHECKPOINT_CATEGORY
            )
    );

    private static String pendingCheckpointWorld;
    private static boolean checkpointRestoreStarted;
    private static boolean checkpointRestoreShutdownRequested;
    private static boolean checkpointRestoreDisconnectRequested;
    private static boolean seamlessRollback;
    private static boolean checkpointSaveInProgress;
    private static boolean saveWasDown;
    private static boolean loadWasDown;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(AfkRollbackClient::tick);
        LOGGER.info("OneWorldRollback loaded; Backup and Rollback controls registered");
    }

    private static void tick(Minecraft client) {
        if (pendingCheckpointWorld != null) {
            processPendingCheckpointRestore(client);
            return;
        }

        if (checkpointRestoreStarted) {
            // Leave the title panorama visible until the restored world is ready.
            if (client.level != null && client.hasSingleplayerServer()) {
                checkpointRestoreStarted = false;
                seamlessRollback = false;
                client.setScreen(null);
            }
            return;
        }

        boolean saveDown = SAVE_CHECKPOINT_KEY.isDown();
        boolean loadDown = LOAD_CHECKPOINT_KEY.isDown();

        // Use the binding's live state rather than consumeClick(). This makes the
        // action work reliably for both keyboard and mouse bindings, including
        // bindings that were initially unbound and assigned later in Controls.
        boolean savePressed = saveDown && !saveWasDown;
        boolean loadPressed = loadDown && !loadWasDown;
        saveWasDown = saveDown;
        loadWasDown = loadDown;

        if (savePressed && client.player != null && client.level != null && client.isSingleplayer()) {
            createCheckpoint(client);
        }

        if (loadPressed && client.player != null && client.level != null && client.isSingleplayer()) {
            requestCheckpointRestore();
        }
    }

    private static void createCheckpoint(Minecraft client) {
        if (checkpointSaveInProgress) {
            LOGGER.info("Checkpoint save already in progress; ignoring save request.");
            return;
        }

        IntegratedServer server = client.getSingleplayerServer();
        if (server == null || client.level == null) return;

        checkpointSaveInProgress = true;

        try {
            Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path checkpoint = world.resolve(CHECKPOINT_DIR).normalize();
            Path checkpointTemp = world.resolve(CHECKPOINT_TEMP_DIR).normalize();
            if (!checkpoint.startsWith(world) || !checkpointTemp.startsWith(world)) {
                throw new IOException("Unsafe checkpoint path");
            }

            LOGGER.info("Saving checkpoint: {}", checkpoint);
            client.player.displayClientMessage(Component.literal("Saving checkpoint..."), true);

            server.execute(() -> {
                try {
                    LOGGER.info("Saving world before checkpoint...");
                    server.saveEverything(false, true, true);

                    LOGGER.info("Copying world to checkpoint: {}", checkpoint);
                    // The current checkpoint remains valid until the complete new
                    // checkpoint has been copied. This matters for large worlds.
                    deleteTree(checkpointTemp);
                    copyTree(world, checkpointTemp, CHECKPOINT_DIR, CHECKPOINT_TEMP_DIR, CHECKPOINT_TIMESTAMP_FILE);
                    Files.writeString(
                            checkpointTemp.resolve(CHECKPOINT_TIMESTAMP_FILE),
                            Long.toString(System.currentTimeMillis())
                    );
                    deleteTree(checkpoint);
                    moveTree(checkpointTemp, checkpoint);

                    LOGGER.info("Checkpoint created successfully");
                    client.execute(() -> {
                        checkpointSaveInProgress = false;
                        if (client.player != null) {
                            client.player.displayClientMessage(
                                    Component.literal("Checkpoint saved."), true);
                        }
                    });
                } catch (Exception e) {
                    LOGGER.error("Failed to create checkpoint", e);
                    client.execute(() -> {
                        checkpointSaveInProgress = false;
                        if (client.player != null) {
                            client.player.displayClientMessage(
                                    Component.literal("Checkpoint save failed: " + e.getMessage()), true);
                        }
                    });
                }
            });
        } catch (Exception e) {
            checkpointSaveInProgress = false;
            LOGGER.error("Could not start checkpoint save", e);
            if (client.player != null) {
                client.player.displayClientMessage(
                        Component.literal("Checkpoint save failed: " + e.getMessage()), true);
            }
        }
    }

    public static boolean isSeamlessRollback() {
        return seamlessRollback;
    }

    public static boolean hasCheckpoint() {
        Minecraft client = Minecraft.getInstance();
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) return false;
        try {
            Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path checkpoint = world.resolve(CHECKPOINT_DIR).normalize();
            return checkpoint.startsWith(world)
                    && Files.isDirectory(checkpoint)
                    && Files.exists(checkpoint.resolve("level.dat"));
        } catch (Exception e) {
            return false;
        }
    }

    public static String getCheckpointButtonText() {
        Minecraft client = Minecraft.getInstance();
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) return "Load Last Checkpoint";

        try {
            Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path timestampFile = world.resolve(CHECKPOINT_DIR).resolve(CHECKPOINT_TIMESTAMP_FILE);
            if (!Files.exists(timestampFile)) return "Load Last Checkpoint";

            long savedAt = Long.parseLong(Files.readString(timestampFile).trim());
            long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - savedAt) / 1000L);
            long days = elapsedSeconds / 86400L;
            long hours = (elapsedSeconds % 86400L) / 3600L;
            long minutes = (elapsedSeconds % 3600L) / 60L;
            long seconds = elapsedSeconds % 60L;

            StringBuilder age = new StringBuilder();
            if (days > 0) age.append(days).append("d ");
            if (hours > 0) age.append(hours).append("h ");
            if (minutes > 0) age.append(minutes).append("m ");
            if (seconds > 0 || age.isEmpty()) age.append(seconds).append("s");
            else age.setLength(age.length() - 1);

            return "Roll back to " + age + " ago";
        } catch (Exception e) {
            return "Load Last Checkpoint";
        }
    }

    public static void requestCheckpointRestore() {
        Minecraft client = Minecraft.getInstance();
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null || client.level == null) return;

        if (!hasCheckpoint()) {
            if (client.player != null) {
                client.player.displayClientMessage(Component.literal("No checkpoint has been saved."), true);
            }
            return;
        }

        try {
            Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            pendingCheckpointWorld = world.getFileName().toString();
            checkpointRestoreStarted = false;
            checkpointRestoreShutdownRequested = false;
            checkpointRestoreDisconnectRequested = false;
            seamlessRollback = true;
            // Show only the normal Minecraft panorama immediately.
            // This avoids both the title-screen fade and the title/menu UI.
            client.setScreen(new PanoramaScreen());
            LOGGER.info("Checkpoint restore requested for world {}", pendingCheckpointWorld);
        } catch (Exception e) {
            LOGGER.error("Could not start checkpoint restore", e);
            pendingCheckpointWorld = null;
        }
    }

    private static void processPendingCheckpointRestore(Minecraft client) {
        if (checkpointRestoreStarted || pendingCheckpointWorld == null) return;

        IntegratedServer server = client.getSingleplayerServer();

        if (server != null && !server.isStopped()) {
            if (!checkpointRestoreShutdownRequested) {
                checkpointRestoreShutdownRequested = true;
                LOGGER.info("Requesting integrated server shutdown before checkpoint restore...");
                server.execute(() -> server.halt(false));
            }
            return;
        }

        if (client.level != null || client.hasSingleplayerServer()) {
            if (!checkpointRestoreDisconnectRequested) {
                checkpointRestoreDisconnectRequested = true;
                LOGGER.info("Integrated server stopped; disconnecting client world without a screen...");
                // Detach the client world without opening the normal disconnect/title screen.
                // WorldOpenFlows below will create a fresh integrated server and reconnect us.
                client.disconnectFromWorld(Component.literal("Rollback"));
            }
            return;
        }

        checkpointRestoreStarted = true;
        seamlessRollback = true;
        String worldName = pendingCheckpointWorld;
        pendingCheckpointWorld = null;
        checkpointRestoreShutdownRequested = false;
        checkpointRestoreDisconnectRequested = false;

        Path saves = client.getLevelSource().getBaseDir().toAbsolutePath().normalize();
        Path world = saves.resolve(worldName).normalize();
        Path checkpoint = world.resolve(CHECKPOINT_DIR).normalize();

        try {
            if (!world.startsWith(saves) || !checkpoint.startsWith(world)) {
                throw new IOException("Unsafe world path");
            }
            if (!Files.isDirectory(checkpoint) || !Files.exists(checkpoint.resolve("level.dat"))) {
                throw new IOException("No valid checkpoint exists");
            }

            LOGGER.info("Restoring checkpoint {} -> {}", checkpoint, world);
            // Keep checkpoint in place. It is deliberately persistent and is only
            // replaced when Shift+P creates a new checkpoint.
            deleteTreeExcept(world, CHECKPOINT_DIR);
            copyTree(checkpoint, world, CHECKPOINT_TIMESTAMP_FILE);
            LOGGER.info("Checkpoint restored successfully; checkpoint kept at {}", checkpoint);

            client.createWorldOpenFlows().openWorld(worldName, () -> {
                LOGGER.info("Checkpoint restore world load cancelled");
                seamlessRollback = false;
                checkpointRestoreStarted = false;
            });
        } catch (Exception e) {
            LOGGER.error("Checkpoint restore failed", e);
            seamlessRollback = false;
            checkpointRestoreStarted = false;
            client.setScreen(null);
        }
    }

    private static void copyTree(Path source, Path target, String... ignoredNames) throws IOException {
        java.util.Set<String> ignored = java.util.Set.of(ignoredNames);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                if (!relative.toString().isEmpty() && ignored.contains(relative.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                if (file.getFileName().toString().equals("session.lock")
                        || ignored.contains(file.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }
                Files.copy(file, target.resolve(relative), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTreeExcept(Path root, String directoryToKeep) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(root) && dir.getFileName().toString().equals(directoryToKeep)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                if (!dir.equals(root) && !dir.getFileName().toString().equals(directoryToKeep)) {
                    Files.deleteIfExists(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void moveTree(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
