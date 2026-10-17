package ch.alya.skydimension;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockListener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.EntityListener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.config.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

public class SkyDimension extends JavaPlugin {

    private static final Logger LOG = Logger.getLogger("Minecraft");

    private Configuration cfg;
    private World skyWorld;

    private boolean playersCanUseCommand;
    private boolean fallToOverworld;
    private boolean createReturnPortal;
    private int searchRadius;
    private int portalSearchRadius;
    private int dreamChance;
    private final Random random = new Random();

    private final Map<String, Location> returnPoints = new HashMap<String, Location>();

    /** Own portal timer per player, replaces Poseidon's transfer (crashes for plugin worlds). */
    private static final int PORTAL_TICKS = 80; // same 4 s as vanilla
    private final Map<String, PortalTimer> portalTimers = new HashMap<String, PortalTimer>();

    private static class PortalTimer {
        int ticks;          // ticks spent inside
        boolean waitExit;   // just arrived: count only after stepping out once
    }

    // ================================================================== lifecycle

    public void onEnable() {
        cfg = getConfiguration();
        cfg.load();

        String worldName = cfg.getString("world-name", "world_sky");
        playersCanUseCommand = cfg.getBoolean("players-can-use-command", false);
        fallToOverworld = cfg.getBoolean("fall-to-overworld", true);
        createReturnPortal = cfg.getBoolean("create-return-portal", true);
        searchRadius = cfg.getInt("safe-spot-search-radius", 48);
        portalSearchRadius = cfg.getInt("portal-search-radius", 16);
        dreamChance = cfg.getInt("dream-chance", 10);

        cfg.setProperty("world-name", worldName);
        cfg.setProperty("players-can-use-command", playersCanUseCommand);
        cfg.setProperty("fall-to-overworld", fallToOverworld);
        cfg.setProperty("create-return-portal", createReturnPortal);
        cfg.setProperty("safe-spot-search-radius", searchRadius);
        cfg.setProperty("portal-search-radius", portalSearchRadius);
        cfg.setProperty("dream-chance", dreamChance);
        cfg.save();

        skyWorld = getServer().getWorld(worldName);
        if (skyWorld == null) {
            LOG.info("[SkyDimension] Loading/creating sky world '" + worldName + "'...");
            // same seed as the overworld (level-seed), like the Nether
            skyWorld = getServer().createWorld(worldName, World.Environment.SKYLANDS, mainWorld().getSeed());
        }

        PluginManager pm = getServer().getPluginManager();
        PlayerListener pl = new Players();
        pm.registerEvent(Event.Type.PLAYER_MOVE, pl, Event.Priority.Normal, this);
        pm.registerEvent(Event.Type.ENTITY_PORTAL_ENTER, new Entities(), Event.Priority.Normal, this);
        pm.registerEvent(Event.Type.PLAYER_BUCKET_EMPTY, pl, Event.Priority.Monitor, this);
        pm.registerEvent(Event.Type.PLAYER_BED_ENTER, pl, Event.Priority.Monitor, this);
        pm.registerEvent(Event.Type.BLOCK_PHYSICS, new Blocks(), Event.Priority.Normal, this);

        getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() {
                tickPortals();
            }
        }, 1L, 1L);

        LOG.info("[SkyDimension] Enabled. Sky world: " + skyWorld.getName()
                + ", /sky for players: " + (playersCanUseCommand ? "on" : "off"));
    }

    public void onDisable() {
        returnPoints.clear();
        portalTimers.clear();
    }

    private World mainWorld() {
        return getServer().getWorlds().get(0);
    }

    // ================================================================== command

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("toggle")) {
            if (!sender.hasPermission("skydimension.toggle")) {
                sender.sendMessage("§cYou may not change this.");
                return true;
            }
            playersCanUseCommand = !playersCanUseCommand;
            cfg.setProperty("players-can-use-command", playersCanUseCommand);
            cfg.save();
            sender.sendMessage("§e/sky for players is now " + (playersCanUseCommand ? "§aON" : "§cOFF")
                    + "§e. Ops and skydimension.command can always use it.");
            return true;
        }

        Player target;
        if (args.length >= 1) {
            if (!sender.hasPermission("skydimension.others")) {
                sender.sendMessage("§cYou may not send other players.");
                return true;
            }
            target = getServer().getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found: " + args[0]);
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
            if (!playersCanUseCommand && !sender.hasPermission("skydimension.command")) {
                sender.sendMessage("§cThe sky can only be reached through a glowstone portal.");
                return true;
            }
        } else {
            sender.sendMessage("Usage from console: /sky <player> | /sky toggle");
            return true;
        }

        if (target.getWorld() == skyWorld) {
            sendBack(target);
        } else {
            sendToSky(target);
        }
        return true;
    }

    /** Overworld -> Sky, 1:1 coordinates. */
    private void sendToSky(Player p) {
        Location from = p.getLocation();
        returnPoints.put(p.getName(), from);
        Location dest = safeSpot(skyWorld, from.getBlockX(), from.getBlockZ());
        dest.setYaw(from.getYaw());
        p.teleport(dest);
    }

    /** Sky -> where the player came from (or main world spawn). */
    private void sendBack(Player p) {
        Location back = returnPoints.remove(p.getName());
        if (back == null || back.getWorld() == null) {
            back = mainWorld().getSpawnLocation();
        }
        p.teleport(back);
    }

    // ================================================================== safe spots

    private boolean standable(World w, int x, int y, int z) {
        if (y < 1 || y > 126) return false;
        return Portals.ground(w.getBlockTypeIdAt(x, y - 1, z))
                && Portals.passable(w.getBlockTypeIdAt(x, y, z))
                && Portals.passable(w.getBlockTypeIdAt(x, y + 1, z));
    }

    /**
     * Highest standable spot, searched in rings around x/z. If nothing is found
     * (Sky: open void), a 3x3 platform of decaying leaves is built at y = 64.
     */
    private Location safeSpot(World w, int cx, int cz) {
        for (int r = 0; r <= searchRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    int x = cx + dx, z = cz + dz;
                    w.loadChunk(x >> 4, z >> 4);
                    int y = w.getHighestBlockYAt(x, z);
                    if (standable(w, x, y, z)) return new Location(w, x + 0.5, y, z + 0.5);
                }
            }
        }
        int y = 64;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                // oak leaves with the decay flag (bit 8): vanilla removes them on its own
                w.getBlockAt(cx + dx, y, cz + dz).setTypeIdAndData(Portals.LEAVES, (byte) 8, false);
                w.getBlockAt(cx + dx, y + 1, cz + dz).setType(Material.AIR);
                w.getBlockAt(cx + dx, y + 2, cz + dz).setType(Material.AIR);
            }
        }
        return new Location(w, cx + 0.5, y + 1, cz + 0.5);
    }

    // ================================================================== portal travel

    private Location portalDestination(Location from) {
        World target = from.getWorld() == skyWorld ? mainWorld() : skyWorld;
        int x = from.getBlockX(), z = from.getBlockZ();

        // search at least as far as safeSpot() may build, or portals get stacked
        int radius = Math.max(portalSearchRadius, searchRadius + 4);
        Block existing = Portals.findNear(target, x, z, radius);
        if (existing != null) return Portals.exitOf(existing);

        Location spot = safeSpot(target, x, z);
        if (!createReturnPortal) return spot;
        return Portals.build(target, spot.getBlockX(), spot.getBlockY(), spot.getBlockZ());
    }

    // ================================================================== listeners

    private class Players extends PlayerListener {

        public void onPlayerMove(PlayerMoveEvent event) {
            if (!fallToOverworld || event.isCancelled()) return;
            Location to = event.getTo();
            if (to.getWorld() != skyWorld || to.getY() >= 0) return;
            // Like the Aether: fall out of the sky, land on the overworld surface below.
            Location dest = safeSpot(mainWorld(), to.getBlockX(), to.getBlockZ());
            dest.setYaw(to.getYaw());
            dest.setPitch(to.getPitch());
            event.getPlayer().setFallDistance(0);
            // setTo instead of teleport() inside the event, and land on ground:
            // a mid-air arrival made the client hover into the fly kick.
            event.setTo(dest);
        }

        public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
            if (event.isCancelled() || event.getBucket() != Material.WATER_BUCKET) return;
            final Block target = event.getBlockClicked().getRelative(event.getBlockFace());
            World w = target.getWorld();
            if (w != skyWorld && w != mainWorld()) return;
            if (!event.getPlayer().hasPermission("skydimension.portal.create")) return;
            int id = target.getTypeId();
            if (id != Portals.AIR && id != Portals.WATER && id != Portals.STATIONARY_WATER) return;
            if (Portals.findInterior(target) == null) return;

            // Let the water land first (bucket empties normally), replace it next tick.
            getServer().getScheduler().scheduleSyncDelayedTask(SkyDimension.this, new Runnable() {
                public void run() {
                    List<Block> inside = Portals.findInterior(target);
                    if (inside == null) return;
                    Portals.light(inside);
                }
            }, 1L);
        }


        /**
         * Notch, feber.se: "when you go to bed there is a certain chance that you get
         * dragged into the dream world instead". Rolled when the player lies down,
         * pulled over just before the screen is fully black (sleep timer 100).
         */
        public void onPlayerBedEnter(PlayerBedEnterEvent event) {
            if (event.isCancelled() || dreamChance <= 0) return;
            final Player p = event.getPlayer();
            if (p.getWorld() != mainWorld()) return;
            if (random.nextInt(100) >= dreamChance) return;

            getServer().getScheduler().scheduleSyncDelayedTask(SkyDimension.this, new Runnable() {
                public void run() {
                    if (!p.isOnline() || !p.isSleeping() || p.getWorld() != mainWorld()) return;
                    final Location bed = p.getLocation();
                    // wake up like in the morning: reset timer, update sleepers, set bed spawn
                    ((CraftPlayer) p).getHandle().a(true, true, true);
                    getServer().getScheduler().scheduleSyncDelayedTask(SkyDimension.this, new Runnable() {
                        public void run() {
                            if (!p.isOnline() || p.getWorld() != mainWorld()) return;
                            Location dest = safeSpot(skyWorld, bed.getBlockX(), bed.getBlockZ());
                            dest.setYaw(bed.getYaw());
                            p.setFallDistance(0);
                            p.teleport(dest);
                        }
                    }, 1L);
                }
            }, 95L);
        }
    }

    /** Glowstone portal block touched by the player's hitbox (feet and head height), or null. */
    private Block touchedGlowstonePortal(Player p) {
        Location l = p.getLocation();
        World w = l.getWorld();
        double x = l.getX(), y = l.getY(), z = l.getZ();
        for (int dy = 0; dy <= 1; dy++) {
            int by = (int) Math.floor(y) + dy;
            for (int ix = (int) Math.floor(x - 0.3); ix <= (int) Math.floor(x + 0.3); ix++) {
                for (int iz = (int) Math.floor(z - 0.3); iz <= (int) Math.floor(z + 0.3); iz++) {
                    if (w.getBlockTypeIdAt(ix, by, iz) != Portals.PORTAL) continue;
                    Block b = w.getBlockAt(ix, by, iz);
                    if (Portals.isGlowstonePortal(b)) return b;
                }
            }
        }
        return null;
    }

    /** Runs every server tick: own portal timer, independent of movement packets. */
    private void tickPortals() {
        for (Player p : getServer().getOnlinePlayers()) {
            World w = p.getWorld();
            String name = p.getName();
            if (w != skyWorld && w != mainWorld()) {
                portalTimers.remove(name);
                continue;
            }
            Block portal = touchedGlowstonePortal(p);
            PortalTimer t = portalTimers.get(name);
            if (portal == null) {
                if (t != null) {
                    t.ticks = 0;
                    t.waitExit = false;
                }
                continue;
            }
            // keep vanilla's portal cooldown up so Poseidon never starts its own transfer
            ((CraftPlayer) p).getHandle().D = 10;
            if (!p.hasPermission("skydimension.portal.use")) continue;
            if (t == null) {
                t = new PortalTimer();
                portalTimers.put(name, t);
            }
            if (t.waitExit) continue;
            if (++t.ticks < PORTAL_TICKS) continue;

            t.ticks = 0;
            t.waitExit = true;
            Location dest = portalDestination(p.getLocation());
            p.setFallDistance(0);
            p.teleport(dest);
        }
    }

    private class Entities extends EntityListener {
        public void onEntityPortalEnter(EntityPortalEnterEvent event) {
            if (!(event.getEntity() instanceof Player)) return;
            if (!Portals.isGlowstonePortal(event.getLocation().getBlock())) return;
            // block Poseidon's transfer already within this tick (crashes for plugin worlds)
            ((CraftPlayer) event.getEntity()).getHandle().D = 10;
        }
    }

    private class Blocks extends BlockListener {
        public void onBlockPhysics(BlockPhysicsEvent event) {
            Block b = event.getBlock();
            if (b.getTypeId() != Portals.PORTAL) return;
            // Vanilla portal physics only accept obsidian. Keep intact glowstone portals,
            // let broken ones collapse the vanilla way.
            if (Portals.isGlowstonePortal(b) && Portals.isIntact(b)) {
                event.setCancelled(true);
            }
        }
    }
}
