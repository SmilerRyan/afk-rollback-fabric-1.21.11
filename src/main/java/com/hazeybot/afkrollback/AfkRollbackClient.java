package com.hazeybot.afkrollback;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Properties;

public final class AfkRollbackClient implements ClientModInitializer {
    public static final String MOD_ID = "afk-rollback";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final long DEFAULT_AFK_SECONDS = 10;
    private static long afkTicks = 20 * DEFAULT_AFK_SECONDS;

    private static double lastX;
    private static double lastY;
    private static double lastZ;
    private static boolean haveLastPosition;
    private static long stillTicks;
    private static boolean snapshotThisIdlePeriod;

    private static String pendingRollbackWorld;
    private static boolean rollbackStarted;
    private static boolean rollbackShutdownRequested;
    private static boolean rollbackDisconnectRequested;

    private static final String SNAPSHOT_DIR = "afk-rollback";
    private static final String SNAPSHOT_TEMP_DIR = "afk-rollback.tmp";

    @Override
    public void onInitializeClient() {
        loadConfig();
        ClientTickEvents.END_CLIENT_TICK.register(AfkRollbackClient::tick);
        LOGGER.info("AFK Rollback loaded; AFK delay = {} seconds", afkTicks / 20);
    }

    private static void loadConfig() {
        Path config = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("afk-rollback.properties");
        Properties properties = new Properties();
        try {
            if (Files.exists(config)) {
                try (InputStream in = Files.newInputStream(config)) {
                    properties.load(in);
                }
            } else {
                Files.createDirectories(config.getParent());
                properties.setProperty("afk_seconds", Long.toString(DEFAULT_AFK_SECONDS));
                try (var out = Files.newOutputStream(config)) {
                    properties.store(out, "AFK Rollback settings");
                }
            }
            long seconds = Long.parseLong(properties.getProperty("afk_seconds", Long.toString(DEFAULT_AFK_SECONDS)));
            seconds = Math.max(1, Math.min(seconds, 3600));
            afkTicks = seconds * 20;
        } catch (Exception e) {
            LOGGER.warn("Could not read config; using {} seconds", DEFAULT_AFK_SECONDS, e);
            afkTicks = DEFAULT_AFK_SECONDS * 20;
        }
    }

    private static void tick(Minecraft client) {
        if (pendingRollbackWorld != null) {
            processPendingRollback(client);
            return;
        }

        Player player = client.player;
        if (player == null || client.level == null || !client.isSingleplayer()) {
            resetMovementTracking();
            return;
        }

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();

        if (!haveLastPosition || x != lastX || y != lastY || z != lastZ) {
            lastX = x;
            lastY = y;
            lastZ = z;
            haveLastPosition = true;
            stillTicks = 0;
            snapshotThisIdlePeriod = false;
            return;
        }

        stillTicks++;
        if (!snapshotThisIdlePeriod && stillTicks >= afkTicks) {
            snapshotThisIdlePeriod = true;
            createSnapshot(client);
        }
    }

    private static void resetMovementTracking() {
        haveLastPosition = false;
        stillTicks = 0;
        snapshotThisIdlePeriod = false;
    }

    private static void createSnapshot(Minecraft client) {
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null || client.level == null) return;

        try {
            Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path saves = world.getParent();
            if (saves == null) return;

            Path snapshot = world.resolve(SNAPSHOT_DIR).normalize();
            Path snapshotTemp = world.resolve(SNAPSHOT_TEMP_DIR).normalize();
            if (!snapshot.startsWith(world) || !snapshotTemp.startsWith(world)) {
                LOGGER.error("Refusing unsafe snapshot path: {}", snapshot);
                return;
            }

            LOGGER.info("Creating AFK snapshot: {}", snapshot);
            client.player.displayClientMessage(Component.literal("Creating AFK rollback snapshot..."), true);

            /*
             * The integrated server owns the world/chunk state. Queue the save
             * and copy on its thread rather than doing it from the client tick
             * thread, which can race the chunk system.
             */
            server.execute(() -> {
                try {
                    LOGGER.info("Saving world before AFK snapshot...");
                    server.saveEverything(false, true, true);

                    LOGGER.info("Copying world to AFK snapshot: {}", snapshot);
                    // Keep the existing snapshot intact until the replacement has
                    // been copied completely. This matters for large worlds and
                    // also means a failed copy cannot destroy the last good snapshot.
                    deleteTree(snapshotTemp);
                    copyTree(world, snapshotTemp, SNAPSHOT_DIR, SNAPSHOT_TEMP_DIR);
                    deleteTree(snapshot);
                    moveTree(snapshotTemp, snapshot);

                    LOGGER.info("AFK snapshot created successfully");
                    client.execute(() -> {
                        if (client.player != null) {
                            client.player.displayClientMessage(
                                    Component.literal("AFK rollback snapshot created."), true);
                        }
                    });
                } catch (Exception e) {
                    LOGGER.error("Failed to create AFK snapshot", e);
                    client.execute(() -> {
                        if (client.player != null) {
                            client.player.displayClientMessage(
                                    Component.literal("AFK snapshot failed: " + e.getMessage()), true);
                        }
                    });
                }
            });
        } catch (Exception e) {
            LOGGER.error("Failed to create AFK snapshot", e);
            if (client.player != null) {
                client.player.displayClientMessage(Component.literal("AFK snapshot failed: " + e.getMessage()), true);
            }
        }
    }

    public static boolean hasSnapshot() {
        Minecraft client = Minecraft.getInstance();
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) return false;
        try {
            Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path saves = world.getParent();
            if (saves == null) return false;
            Path snapshot = saves.resolve(world.getFileName().toString() + "-afk").normalize();
            return snapshot.startsWith(saves) && Files.isDirectory(snapshot) && Files.exists(snapshot.resolve("level.dat"));
        } catch (Exception e) {
            return false;
        }
    }

    public static void requestRollback() {
        Minecraft client = Minecraft.getInstance();
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null || client.level == null) return;

        try {
            Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            pendingRollbackWorld = world.getFileName().toString();
            rollbackStarted = false;
            rollbackShutdownRequested = false;
            rollbackDisconnectRequested = false;
            LOGGER.info("Rollback requested for world {}", pendingRollbackWorld);
        } catch (Exception e) {
            LOGGER.error("Could not start rollback", e);
            pendingRollbackWorld = null;
        }
    }

    private static void processPendingRollback(Minecraft client) {
        if (rollbackStarted || pendingRollbackWorld == null) return;

        IntegratedServer server = client.getSingleplayerServer();

        // First ask the integrated server itself to shut down. This must happen
        // on the server thread so its final world save finishes before any
        // world files are touched.
        if (server != null && !server.isStopped()) {
            if (!rollbackShutdownRequested) {
                rollbackShutdownRequested = true;
                LOGGER.info("Requesting integrated server shutdown before rollback...");
                server.execute(() -> server.halt(false));
            }
            return;
        }

        // Only disconnect after the server has completely stopped. Then wait
        // until Minecraft has released the integrated-server/world references.
        if (client.level != null || client.hasSingleplayerServer()) {
            if (!rollbackDisconnectRequested) {
                rollbackDisconnectRequested = true;
                LOGGER.info("Integrated server stopped; disconnecting world...");
                client.disconnect(new TitleScreen(), false, true);
            }
            return;
        }

        rollbackStarted = true;
        String worldName = pendingRollbackWorld;
        pendingRollbackWorld = null;
        rollbackShutdownRequested = false;
        rollbackDisconnectRequested = false;

        Path saves = client.getLevelSource().getBaseDir().toAbsolutePath().normalize();
        Path world = saves.resolve(worldName).normalize();
        Path snapshot = saves.resolve(worldName + "-afk").normalize();

        try {
            if (!world.startsWith(saves) || !snapshot.startsWith(saves)) {
                throw new IOException("Unsafe world path");
            }
            if (!Files.isDirectory(snapshot) || !Files.exists(snapshot.resolve("level.dat"))) {
                throw new IOException("No valid AFK snapshot exists");
            }

            LOGGER.info("Restoring AFK snapshot {} -> {}", snapshot, world);
            // Keep afk-rollback in place. It is the persistent checkpoint and
            // must survive both restoration and the subsequent world load.
            deleteTreeExcept(world, SNAPSHOT_DIR);
            copyTree(snapshot, world);
            LOGGER.info("AFK snapshot restored successfully; checkpoint kept at {}", snapshot);

            client.createWorldOpenFlows().openWorld(worldName, () -> {
                LOGGER.info("AFK rollback load cancelled");
            });
        } catch (Exception e) {
            LOGGER.error("AFK rollback failed", e);
            client.setScreen(new TitleScreen());
        } finally {
            rollbackStarted = false;
        }
    }

    private static void copyTree(Path source, Path target, String... excludedRootDirectories) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                if (!relative.toString().isEmpty() && (relative.toString().equals(SNAPSHOT_DIR) || relative.toString().equals(SNAPSHOT_TEMP_DIR))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                if (relative.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
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
