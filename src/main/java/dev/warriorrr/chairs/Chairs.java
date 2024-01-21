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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.spigotmc.event.entity.EntityDismountEvent;
import org.spigotmc.event.entity.EntityMountEvent;

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
            dismount(player, Bukkit.isStopping());
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

        dismount(player, event.getDismounted(), false);
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
        dismount(event.getPlayer(), true);
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
                dismount(player, armorStand, false);
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

        mountLocations.put(player.getUniqueId(), player.getLocation());

        block.getWorld().spawn(location, ArmorStand.class, armorStand -> {
            armorStand.setMarker(true);
            armorStand.setInvisible(true);
            armorStand.setInvulnerable(true);

            // ArmorStand#addPassenger is for some reason not calling the mount event, call manually for compatibility reasons
            if (!armorStand.addPassenger(player) || !new EntityMountEvent(player, armorStand).callEvent()) {
                armorStand.remove();
                mountLocations.remove(player.getUniqueId());
                return;
            }

            chairs.put(armorStand.getUniqueId(), block.getLocation());
            chairLocations.put(block.getLocation(), armorStand.getUniqueId());
        });
    }

    public void dismount(Player player, boolean quitting) {
        final Entity armorStand = player.getVehicle();
        if (armorStand != null)
            dismount(player, armorStand, quitting);
    }

    public void dismount(Player player, Entity armorStand, boolean quitting) {
        if (chairs.containsKey(armorStand.getUniqueId())) {
            chairs.remove(armorStand.getUniqueId());
            chairLocations.remove(armorStand.getLocation().getBlock().getLocation());
            armorStand.remove();

            Location dismountLocation = Optional.ofNullable(mountLocations.remove(player.getUniqueId())).orElse(player.getLocation().add(0, 1.05, 0));

            dismountLocation.setYaw(player.getLocation().getYaw());
            dismountLocation.setPitch(player.getLocation().getPitch());

            if (quitting)
                player.teleport(dismountLocation);
            else
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
