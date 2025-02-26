package dev.warriorrr.chairs;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected.Half;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
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
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Chairs extends JavaPlugin implements Listener {
    private static final int MAX_DISTANCE = (int) Math.pow(2, 2) + 1;
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
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getItem() != null)
            return;

        sit(event.getClickedBlock(), event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        blockChanged(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getDismounted().getType() != EntityType.ARMOR_STAND)
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
        final Entity armorStand = occupied(block);

        if (armorStand != null) {
            if (!armorStand.getPassengers().isEmpty() && armorStand.getPassengers().get(0) instanceof Player player)
                dismount(player, armorStand);
        }
    }

    private void sit(Block block, Player player) {
        // The block is not a chair, the player is already sitting or someone is already sitting there.
        if (!isValid(block) || mountLocations.containsKey(player.getUniqueId()) || isOccupied(block))
            return;

        final Location location = block.getLocation();
        if (location.distanceSquared(player.getLocation()) > MAX_DISTANCE)
            return;

        location.add(0.5, 0.3, 0.5);

        if (block.getBlockData() instanceof Directional dir)
            location.setDirection(dir.getFacing().getOppositeFace().getDirection());

        final ArmorStand armorStand = block.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.setMarker(true);
            stand.setInvisible(true);
            stand.setInvulnerable(true);
        });

        final Location originalPlayerLoc = player.getLocation();

        if (!armorStand.addPassenger(player)) {
            armorStand.remove();
            return;
        }

        mountLocations.put(player.getUniqueId(), originalPlayerLoc);
        chairs.put(armorStand.getUniqueId(), block.getLocation());
        chairLocations.put(block.getLocation(), armorStand.getUniqueId());
    }

    public void dismount(Player player) {
        final Entity armorStand = player.getVehicle();
        if (armorStand != null)
            dismount(player, armorStand);
    }

    public void dismount(Player player, Entity armorStand) {
        if (chairs.containsKey(armorStand.getUniqueId())) {
            chairs.remove(armorStand.getUniqueId());
            chairLocations.remove(armorStand.getLocation().getBlock().getLocation());
            armorStand.remove();

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

        return Bukkit.getEntity(uuid);
    }

    public boolean isOccupied(Block block) {
        return chairLocations.get(block.getLocation()) != null;
    }

    private boolean isValid(Block block) {
        if (!Tag.STAIRS.isTagged(block.getType()) || !(block.getBlockData() instanceof Stairs stairs))
            return false;

        return stairs.getHalf() == Half.BOTTOM && block.getRelative(BlockFace.UP).isPassable();
    }
}
