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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.zip.CRC32;
import java.util.HashSet;
import java.util.Set;

public final class AfkRollbackClient implements ClientModInitializer {
    public static final String MOD_ID = "oneworldrollback";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final String CHECKPOINT_FILE = "checkpoint.zip";
    private static final String CHECKPOINT_TEMP_FILE = "checkpoint.zip.tmp";

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
            Path checkpoint = world.resolve(CHECKPOINT_FILE).normalize();
            Path checkpointTemp = world.resolve(CHECKPOINT_TEMP_FILE).normalize();
            if (!checkpoint.startsWith(world) || !checkpointTemp.startsWith(world)) {
                throw new IOException("Unsafe checkpoint path");
            }

            LOGGER.info("Saving checkpoint: {}", checkpoint);
            client.player.displayClientMessage(Component.literal("Saving checkpoint..."), true);

            server.execute(() -> {
                try {
                    LOGGER.info("Saving world before checkpoint...");
                    server.saveEverything(false, true, true);

                    LOGGER.info("Compressing world into checkpoint: {}", checkpoint);
                    // Write a complete new ZIP first. The old checkpoint remains valid
                    // until the new one has finished, which matters for large worlds.
                    Files.deleteIfExists(checkpointTemp);
                    zipWorld(world, checkpointTemp);
                    Files.move(checkpointTemp, checkpoint, StandardCopyOption.REPLACE_EXISTING);
                    // The ZIP's modified time is the checkpoint time used by the UI.
                    Files.setLastModifiedTime(checkpoint, FileTime.fromMillis(System.currentTimeMillis()));

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
            Path checkpoint = world.resolve(CHECKPOINT_FILE).normalize();
            return checkpoint.startsWith(world)
                    && Files.isRegularFile(checkpoint)
                    && Files.size(checkpoint) > 0;
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
            Path checkpoint = world.resolve(CHECKPOINT_FILE).normalize();
            if (!Files.isRegularFile(checkpoint)) return "Load Last Checkpoint";

            long savedAt = Files.getLastModifiedTime(checkpoint).toMillis();
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
        Path checkpoint = world.resolve(CHECKPOINT_FILE).normalize();

        try {
            if (!world.startsWith(saves) || !checkpoint.startsWith(world)) {
                throw new IOException("Unsafe world path");
            }
            if (!Files.isRegularFile(checkpoint) || Files.size(checkpoint) == 0) {
                throw new IOException("No valid checkpoint exists");
            }

            LOGGER.info("Restoring checkpoint {} -> {}", checkpoint, world);
            // Keep checkpoint.zip in place. It is deliberately persistent and is
            // only replaced when a new checkpoint is created.
            // Restore directly from the ZIP. unzipWorld() skips files whose size + CRC32 already match.
            unzipWorld(checkpoint, world);
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

    private static void zipWorld(Path world, Path zipFile) throws IOException {
        try (OutputStream output = Files.newOutputStream(zipFile);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            // A low compression level makes checkpoint creation much faster while
            // still keeping every file independently compressed in the ZIP.
            zip.setLevel(1);

            Files.walkFileTree(world, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path relative = world.relativize(dir);
                    if (relative.toString().isEmpty()) return FileVisitResult.CONTINUE;

                    String name = relative.toString().replace(java.io.File.separatorChar, '/') + "/";
                    if (name.equals(CHECKPOINT_FILE + "/") || name.equals(CHECKPOINT_TEMP_FILE + "/")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    ZipEntry entry = new ZipEntry(name);
                    zip.putNextEntry(entry);
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String filename = file.getFileName().toString();
                    if (filename.equals("session.lock")
                            || filename.equals(CHECKPOINT_FILE)
                            || filename.equals(CHECKPOINT_TEMP_FILE)) {
                        return FileVisitResult.CONTINUE;
                    }

                    Path relative = world.relativize(file);
                    ZipEntry entry = new ZipEntry(
                            relative.toString().replace(java.io.File.separatorChar, '/'));

                    // Every file is its own ZIP entry, so ZipFile can later open
                    // exactly the file it needs without extracting the archive
                    // from the beginning. Do not use STORED entries here: the
                    // checkpoint format intentionally compresses every file.
                    entry.setMethod(ZipEntry.DEFLATED);

                    zip.putNextEntry(entry);
                    try (InputStream input = Files.newInputStream(file)) {
                        input.transferTo(zip);
                    }
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static void unzipWorld(Path zipFile, Path world) throws IOException {
        // ZipFile uses the ZIP central directory, allowing individual entries to
        // be opened directly. This is substantially faster than ZipInputStream
        // for rollback because unchanged files can be skipped without reading
        // their compressed data at all.
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Set<String> checkpointFiles = new HashSet<>();
            Set<Path> checkpointDirectories = new HashSet<>();

            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName().replace('\\', '/');

                // Prevent a crafted checkpoint.zip from writing outside the world.
                Path target = world.resolve(entryName).normalize();
                if (!target.startsWith(world)) {
                    throw new IOException("Unsafe path in checkpoint ZIP: " + entryName);
                }

                if (entry.isDirectory()) {
                    checkpointDirectories.add(target);
                    continue;
                }

                checkpointFiles.add(target.toString());

                Path parent = target.getParent();
                if (parent != null) Files.createDirectories(parent);

                // If the current file has exactly the same size and CRC32 as the
                // checkpoint entry, it is already identical and does not need to
                // be deleted or extracted. This avoids both disk writes and
                // decompression for unchanged Minecraft files.
                if (Files.isRegularFile(target)) {
                    long currentSize = Files.size(target);
                    if (currentSize == entry.getSize()) {
                        long currentCrc = crc32(target);
                        if (currentCrc == entry.getCrc()) {
                            continue;
                        }
                    }
                }

                LOGGER.debug("Restoring changed file: {}", entryName);
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(
                             target,
                             java.nio.file.StandardOpenOption.CREATE,
                             java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                             java.nio.file.StandardOpenOption.WRITE)) {
                    input.transferTo(output);
                }
            }

            // Remove files that existed in the live world but are not present in
            // the checkpoint. checkpoint.zip itself is deliberately preserved.
            Files.walkFileTree(world, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.getFileName().toString().equals(CHECKPOINT_FILE)
                            || file.getFileName().toString().equals(CHECKPOINT_TEMP_FILE)
                            || file.getFileName().toString().equals("session.lock")) {
                        return FileVisitResult.CONTINUE;
                    }

                    if (!checkpointFiles.contains(file.toString())) {
                        Files.deleteIfExists(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) throw exc;
                    if (dir.equals(world)) return FileVisitResult.CONTINUE;

                    // Delete directories only when they are not represented by
                    // the checkpoint. Non-empty directories are left alone; any
                    // stale files inside them have already been removed above.
                    if (!checkpointDirectories.contains(dir)) {
                        try {
                            Files.deleteIfExists(dir);
                        } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                            // Leave non-empty directory alone.
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static long crc32(Path file) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                crc.update(buffer, 0, read);
            }
        }
        return crc.getValue();
    }

}
