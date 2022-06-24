package net.earthmc.chairs;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.spigotmc.event.entity.EntityDismountEvent;

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
            dismount(player, true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getItem() != null)
            return;

        sit(event.getClickedBlock(), event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Entity entity = occupied(event.getBlock());

        if (entity != null) {
            if (entity.getPassengers().size() > 0 && entity.getPassengers().get(0) instanceof Player player)
                dismount(player, entity, false);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getDismounted().getType() != EntityType.ARMOR_STAND)
            return;

        dismount(player, event.getDismounted(), false);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        dismount(event.getPlayer(), true);
    }

    private void sit(Block block, Player player) {
        // The block is not a chair or the player is already sitting
        if (!isValid(block) || mountLocations.containsKey(player.getUniqueId()))
            return;

        Location location = block.getLocation();
        if (location.distanceSquared(player.getLocation()) > MAX_DISTANCE)
            return;

        location.add(0.5, 0.3, 0.5);

        if (block.getBlockData() instanceof Directional dir)
            location.setDirection(dir.getFacing().getOppositeFace().getDirection());

        mountLocations.put(player.getUniqueId(), player.getLocation());

        UUID uuid = block.getWorld().spawn(location, ArmorStand.class, armorStand -> {
            player.teleport(armorStand);
            armorStand.setMarker(true);
            armorStand.setInvisible(true);
            armorStand.setInvulnerable(false);
            armorStand.addPassenger(player);
        }).getUniqueId();

        chairs.put(uuid, block.getLocation());
        chairLocations.put(block.getLocation(), uuid);
    }

    public void dismount(Player player, boolean quitting) {
        Entity armorStand = player.getVehicle();
        if (armorStand != null)
            dismount(player, armorStand, quitting);
    }

    public void dismount(Player player, Entity armorStand, boolean quitting) {
        if (chairs.containsKey(armorStand.getUniqueId())) {
            chairs.remove(armorStand.getUniqueId());
            chairLocations.remove(armorStand.getLocation().getBlock().getLocation());
            armorStand.remove();

            Location dismountLocation = Optional.ofNullable(mountLocations.get(player.getUniqueId())).orElse(player.getLocation().add(0, 1.05, 0));
            mountLocations.remove(player.getUniqueId());

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

    private boolean isValid(Block block) {
        if (!Tag.STAIRS.isTagged(block.getType()))
            return false;

        return block.getRelative(BlockFace.UP).isPassable();
    }
}
