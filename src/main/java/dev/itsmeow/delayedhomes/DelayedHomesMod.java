package dev.itsmeow.delayedhomes;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3i;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.Dimension;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import net.minecraftforge.fml.server.ServerLifecycleHooks;
import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Predicate;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(DelayedHomesMod.MOD_ID)
@Mod.EventBusSubscriber(modid = DelayedHomesMod.MOD_ID)
public class DelayedHomesMod {

    public static final String MOD_ID = "delayedhomes";

    public static ServerConfig SERVER_CONFIG = null;
    private static ForgeConfigSpec SERVER_CONFIG_SPEC = null;

    public static final String CONFIG_FIELD_NAME = "home_teleport_delay";
    public static final String CONFIG_FIELD_COMMENT = "Sets the teleportation delay, in seconds.";
    public static final int CONFIG_FIELD_VALUE = 5;
    public static final int CONFIG_FIELD_MIN = 0;
    public static final int CONFIG_FIELD_MAX = Integer.MAX_VALUE;

    public static HashMap<UUID, Integer> activeHomeTPRequests = new HashMap<>();
    public static HashMap<UUID, BlockPos> activeHomeTPRequestersPositions = new HashMap<>();

    public DelayedHomesMod() {
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST, () -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (s, b) -> true));
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::loadComplete);
    }

    private void loadComplete(final FMLLoadCompleteEvent event) {
        final Pair<ServerConfig, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(ServerConfig::new);
        SERVER_CONFIG_SPEC = specPair.getRight();
        SERVER_CONFIG = specPair.getLeft();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_CONFIG_SPEC);
    }

    public static class ServerConfig {
        public ForgeConfigSpec.Builder builder;
        public final ForgeConfigSpec.IntValue teleportDelay;

        ServerConfig(ForgeConfigSpec.Builder builder) {
            this.builder = builder;
            this.teleportDelay = builder.comment(DelayedHomesMod.CONFIG_FIELD_COMMENT
                            + " Place a copy of this config in the defaultconfigs/ folder in the main server/.minecraft directory (or make the folder if it's not there) to copy this to new worlds.")
                    .defineInRange(DelayedHomesMod.CONFIG_FIELD_NAME,
                            DelayedHomesMod.CONFIG_FIELD_VALUE,
                            DelayedHomesMod.CONFIG_FIELD_MIN,
                            DelayedHomesMod.CONFIG_FIELD_MAX);
            builder.build();
        }
    }

    private static int getTeleportTimeout() {
        return SERVER_CONFIG.teleportDelay.get();
    }


    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {

        Predicate<CommandSource> isPlayer = source -> {
            try {
                return source.getPlayerOrException() != null;
            } catch (CommandSyntaxException e) {
                return false;
            }
        };

        CommandDispatcher<CommandSource> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("home").requires(isPlayer).executes(command -> {
            ServerPlayerEntity player = command.getSource().getPlayerOrException();
            CompoundNBT homesData = QHWorldStorage.get(player.getCommandSenderWorld()).data;
            if(homesData == null
                    || !homesData.contains("dimension")
                    || !homesData.contains("x")
                    || !homesData.contains("y")
                    || !homesData.contains("z")) {
                player.sendMessage(new StringTextComponent("No home has been set!").setStyle(Style.EMPTY.withColor(TextFormatting.RED)), Util.NIL_UUID);
                return 0;
            } else {
                activeHomeTPRequests.put(player.getUUID(), getTeleportTimeout() * 20);
                activeHomeTPRequestersPositions.put(player.getUUID(), player.blockPosition());
                player.sendMessage(new StringTextComponent("Teleporting to home. Stay still.").setStyle(Style.EMPTY.withColor(TextFormatting.GOLD)), Util.NIL_UUID);
                return 1;
            }
        }));
        dispatcher.register(Commands.literal("sethome").requires(isPlayer).executes(command -> {
            ServerPlayerEntity player = command.getSource().getPlayerOrException();

            QHWorldStorage hd = QHWorldStorage.get(player.getCommandSenderWorld());
            hd.data.putDouble("x", player.getX());
            hd.data.putDouble("y", player.getY());
            hd.data.putDouble("z", player.getZ());
            hd.data.putString("dimension", player.getCommandSenderWorld().dimension().location().toString());
            hd.setDirty();

            player.sendMessage(new StringTextComponent("Home set.").setStyle(Style.EMPTY.withColor(TextFormatting.GOLD)), Util.NIL_UUID);
            return 1;
        }));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();

        HashSet<UUID> toRemove = new HashSet<>();

        for (UUID uuid : activeHomeTPRequests.keySet()) {
            ServerPlayerEntity player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                if (!activeHomeTPRequestersPositions.get(uuid).equals(player.blockPosition()) || player.hurtTime > 0) {
                    toRemove.add(uuid);
                    player.sendMessage(new StringTextComponent("Teleport to home canceled.").setStyle(Style.EMPTY.withColor(TextFormatting.RED)), Util.NIL_UUID);

                } else {
                    int delay = activeHomeTPRequests.get(uuid);
                    if (delay > 0) {
                        delay--;
                        activeHomeTPRequests.put(uuid, delay);
                        if (delay % 20 == 0) {
                            player.sendMessage(new StringTextComponent("Teleporting in " + delay / 20 + " seconds.").setStyle(Style.EMPTY.withColor(TextFormatting.GREEN)), Util.NIL_UUID);
                        }
                    } else {
                        teleportPlayerToHome(player);
                        toRemove.add(uuid);
                        player.sendMessage(new StringTextComponent("Teleported to home.").setStyle(Style.EMPTY.withColor(TextFormatting.GREEN)), Util.NIL_UUID);

                    }
                }
            }
        }
        for (UUID uuid : toRemove) {
            activeHomeTPRequests.remove(uuid);
            activeHomeTPRequestersPositions.remove(uuid);
        }
    }

    private static void teleportPlayerToHome(ServerPlayerEntity player) {
        CompoundNBT data = QHWorldStorage.get(player.getCommandSenderWorld()).data;

        ServerWorld destWorld = player.getCommandSenderWorld()
                .getServer()
                .getLevel(RegistryKey.create(Registry.DIMENSION_REGISTRY, new ResourceLocation(data.getString("dimension"))));

        player.teleportTo(destWorld,
                data.getDouble("x"),
                data.getDouble("y"),
                data.getDouble("z"),
                player.yRot,
                player.xRot);
    }

    public static class QHWorldStorage extends WorldSavedData {
        private static final String DATA_NAME = DelayedHomesMod.MOD_ID + "_HomeData";
        public CompoundNBT data = new CompoundNBT();

        public QHWorldStorage() {
            super(DATA_NAME);
        }

        public QHWorldStorage(String s) {
            super(s);
        }

        public static QHWorldStorage get(World world) {
            return world.getServer().overworld().getDataStorage().computeIfAbsent(QHWorldStorage::new, DATA_NAME);
        }

        @Override
        public void load(CompoundNBT nbt) {
            data = nbt;
        }

        @Override
        public CompoundNBT save(CompoundNBT compound) {
            compound = data;
            return compound;
        }
    }
}


