package dev.warriorrr.chairs;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected.Half;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.NumberConversions;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Chairs extends JavaPlugin implements Listener {
    private static final int MAX_HORIZONTAL_DISTANCE = (int) Math.pow(2, 2) + 1;
    private static final float MAX_VERTICAL_DISTANCE = 1.5f;
    private final Map<UUID, Location> chairs = new ConcurrentHashMap<>();
    private final Map<Location, UUID> chairLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Location> mountLocations = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers())
            dismount(player);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerInteract(PlayerInteractEvent event) {
        final Player player = event.getPlayer();
        // Teleporting the player in the main hand interact event in 26.1 causes the build.tooHigh message to be sent to them
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.OFF_HAND || event.getClickedBlock() == null || !player.getInventory().getItemInMainHand().isEmpty()) {
            return;
        }

        sit(event.getClickedBlock(), player);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        blockChanged(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getDismounted().getType() != EntityType.ITEM_DISPLAY)
            return;

        dismount(player, event.getDismounted());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        final Entity vehicle = event.getPlayer().getVehicle();

        if (vehicle == null || !chairs.containsKey(vehicle.getUniqueId()))
            return;

        chairs.remove(vehicle.getUniqueId());
        chairLocations.remove(vehicle.getLocation().getBlock().getLocation());
        mountLocations.remove(event.getPlayer().getUniqueId());
        vehicle.remove();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        dismount(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (final Block block : event.getBlocks()) {
            blockChanged(block);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (final Block block : event.getBlocks())
            blockChanged(block);
    }

    private void blockChanged(final Block block) {
        final Entity chair = occupied(block);

        if (chair != null && !chair.isEmpty() && chair.getPassengers().get(0) instanceof Player player) {
            dismount(player, chair);
        }
    }

    private void sit(Block block, Player player) {
        // The block is not a chair, the player is already sitting or someone is already sitting there.
        if (!isValid(block) || mountLocations.containsKey(player.getUniqueId()) || isOccupied(block))
            return;

        final double dy = block.getY() - player.getY();
        final double dx = block.getX() - player.getX();
        final double dz = block.getZ() - player.getZ();

        if (Math.abs(dy) > MAX_VERTICAL_DISTANCE || NumberConversions.square(dx) + NumberConversions.square(dz) > MAX_HORIZONTAL_DISTANCE) {
            return;
        }

        final Location location = block.getLocation().add(0.5, 0.5, 0.5);

        if (block.getBlockData() instanceof Directional dir)
            location.setDirection(dir.getFacing().getOppositeFace().getDirection());

        final Entity chair = block.getWorld().spawn(location, ItemDisplay.class, entity -> {
            entity.setInvisible(true);
            entity.setInvulnerable(true);
            entity.setPersistent(false);
        });

        final Location originalPlayerLoc = player.getLocation();

        if (!chair.addPassenger(player)) {
            chair.remove();
            return;
        }

        mountLocations.put(player.getUniqueId(), originalPlayerLoc);
        chairs.put(chair.getUniqueId(), block.getLocation());
        chairLocations.put(block.getLocation(), chair.getUniqueId());
    }

    public void dismount(Player player) {
        final Entity chair = player.getVehicle();
        if (chair != null)
            dismount(player, chair);
    }

    public void dismount(Player player, Entity chair) {
        if (chairs.remove(chair.getUniqueId()) != null) {
            chairLocations.remove(chair.getLocation().getBlock().getLocation());
            chair.remove();

            Location dismountLocation = Optional.ofNullable(mountLocations.remove(player.getUniqueId())).orElse(player.getLocation().add(0, 1.05, 0));

            dismountLocation.setYaw(player.getLocation().getYaw());
            dismountLocation.setPitch(player.getLocation().getPitch());

            player.teleportAsync(dismountLocation);
        }
    }

    @Nullable
    private Entity occupied(Block block) {
        UUID uuid = chairLocations.get(block.getLocation());
        if (uuid == null)
            return null;

        return block.getWorld().getEntity(uuid);
    }

    public boolean isOccupied(Block block) {
        return chairLocations.get(block.getLocation()) != null;
    }

    private boolean isValid(Block block) {
        return Tag.STAIRS.isTagged(block.getType()) && block.getBlockData() instanceof Stairs stairs
                && stairs.getHalf() == Half.BOTTOM && block.getRelative(BlockFace.UP).isPassable();
    }
}
