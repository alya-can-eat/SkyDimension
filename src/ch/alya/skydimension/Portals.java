package ch.alya.skydimension;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/** Glowstone portal logic: frame detection, integrity checks, search and building. */
final class Portals {

    static final int AIR = 0, WATER = 8, STATIONARY_WATER = 9, LAVA = 10, STATIONARY_LAVA = 11,
            LEAVES = 18, WEB = 30, FIRE = 51, CACTUS = 81, GLOWSTONE = 89, PORTAL = 90, COBBLESTONE = 4;

    /** Interior size, fixed like the b1.7.3 nether portal (frame 4x5, corners optional). */
    static final int W = 2, H = 3;

    /** Blocks a player can stand inside. */
    private static final Set<Integer> PASSABLE = new HashSet<Integer>();
    static {
        int[] ids = {0, 6, 31, 32, 37, 38, 39, 40, 50, 55, 59, 66, 75, 76, 78, 83};
        for (int id : ids) PASSABLE.add(id);
    }

    private Portals() {}

    static boolean passable(int id) {
        return PASSABLE.contains(id);
    }

    static boolean ground(int id) {
        return !PASSABLE.contains(id) && id != WATER && id != STATIONARY_WATER && id != LAVA
                && id != STATIONARY_LAVA && id != LEAVES && id != WEB && id != FIRE
                && id != CACTUS && id != PORTAL;
    }

    // ------------------------------------------------------------------ frame detection

    /**
     * Flood-fills the empty space around start in both vertical planes.
     * Returns the 6 interior cells if they form a 2x3 opening fully enclosed by
     * glowstone, otherwise null. Corners are not required, like vanilla.
     */
    static List<Block> findInterior(Block start) {
        List<Block> r = fill(start, true);
        return r != null ? r : fill(start, false);
    }

    private static List<Block> fill(Block start, boolean alongX) {
        World w = start.getWorld();
        int maxCells = W * H;
        List<Block> interior = new ArrayList<Block>();
        Set<String> seen = new HashSet<String>();
        LinkedList<int[]> queue = new LinkedList<int[]>();
        queue.add(new int[]{start.getX(), start.getY(), start.getZ()});
        int minA = Integer.MAX_VALUE, maxA = Integer.MIN_VALUE, minY = 128, maxY = -1;

        while (!queue.isEmpty()) {
            int[] c = queue.removeFirst();
            if (c[1] < 0 || c[1] > 127) return null;
            String key = c[0] + "," + c[1] + "," + c[2];
            if (!seen.add(key)) continue;
            int id = w.getBlockTypeIdAt(c[0], c[1], c[2]);
            if (id == GLOWSTONE) continue;
            if (id != AIR && id != WATER && id != STATIONARY_WATER) return null;
            interior.add(w.getBlockAt(c[0], c[1], c[2]));
            if (interior.size() > maxCells) return null;

            int a = alongX ? c[0] : c[2];
            minA = Math.min(minA, a);
            maxA = Math.max(maxA, a);
            minY = Math.min(minY, c[1]);
            maxY = Math.max(maxY, c[1]);
            if (maxA - minA + 1 > W || maxY - minY + 1 > H) return null;

            queue.add(new int[]{c[0], c[1] + 1, c[2]});
            queue.add(new int[]{c[0], c[1] - 1, c[2]});
            if (alongX) {
                queue.add(new int[]{c[0] + 1, c[1], c[2]});
                queue.add(new int[]{c[0] - 1, c[1], c[2]});
            } else {
                queue.add(new int[]{c[0], c[1], c[2] + 1});
                queue.add(new int[]{c[0], c[1], c[2] - 1});
            }
        }
        if (interior.size() != W * H) return null;
        return interior;
    }

    static void light(List<Block> interior) {
        for (Block b : interior) {
            b.setTypeIdAndData(PORTAL, (byte) 0, false); // no physics, clients still get the update
        }
    }

    // ------------------------------------------------------------------ integrity

    /** Portal axis: true = the portal sheet runs along X. */
    static boolean axisX(World w, int x, int y, int z) {
        int xm = w.getBlockTypeIdAt(x - 1, y, z), xp = w.getBlockTypeIdAt(x + 1, y, z);
        if (xm == PORTAL || xp == PORTAL) return true;
        int zm = w.getBlockTypeIdAt(x, y, z - 1), zp = w.getBlockTypeIdAt(x, y, z + 1);
        if (zm == PORTAL || zp == PORTAL) return false;
        return xm == GLOWSTONE && xp == GLOWSTONE;
    }

    /** Type id at the end of a straight run of portal blocks. */
    private static int endOfRun(World w, int x, int y, int z, int dx, int dy, int dz) {
        for (int i = 1; i <= 22; i++) {
            int yy = y + dy * i;
            if (yy < 0 || yy > 127) return -1;
            int id = w.getBlockTypeIdAt(x + dx * i, yy, z + dz * i);
            if (id != PORTAL) return id;
        }
        return -1;
    }

    /** True if this portal block sits in a glowstone frame (any vanilla obsidian portal returns false). */
    static boolean isGlowstonePortal(Block b) {
        if (b.getTypeId() != PORTAL) return false;
        World w = b.getWorld();
        int x = b.getX(), y = b.getY(), z = b.getZ();
        return endOfRun(w, x, y, z, 0, -1, 0) == GLOWSTONE;
    }

    /** True if the frame around this cell is complete in all four directions. */
    static boolean isIntact(Block b) {
        World w = b.getWorld();
        int x = b.getX(), y = b.getY(), z = b.getZ();
        boolean ax = axisX(w, x, y, z);
        int dx = ax ? 1 : 0, dz = ax ? 0 : 1;
        return endOfRun(w, x, y, z, 0, -1, 0) == GLOWSTONE
                && endOfRun(w, x, y, z, 0, 1, 0) == GLOWSTONE
                && endOfRun(w, x, y, z, dx, 0, dz) == GLOWSTONE
                && endOfRun(w, x, y, z, -dx, 0, -dz) == GLOWSTONE;
    }

    // ------------------------------------------------------------------ search + build

    /** Nearest glowstone portal block (bottom cell) around x/z, or null. */
    static Block findNear(World w, int cx, int cz, int radius) {
        // Unloaded chunks read as air in b1.7.3, so load the whole search area first
        // (+1 block for frames on a chunk border).
        for (int chx = (cx - radius - 1) >> 4; chx <= (cx + radius + 1) >> 4; chx++) {
            for (int chz = (cz - radius - 1) >> 4; chz <= (cz + radius + 1) >> 4; chz++) {
                w.loadChunk(chx, chz);
            }
        }
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                for (int y = 1; y < 127; y++) {
                    if (w.getBlockTypeIdAt(x, y, z) != PORTAL) continue;
                    if (w.getBlockTypeIdAt(x, y - 1, z) != GLOWSTONE) continue; // bottom cell only
                    double d = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                    if (d < bestD) {
                        Block b = w.getBlockAt(x, y, z);
                        if (isIntact(b)) {
                            bestD = d;
                            best = b;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** A free, standable spot right in front of (or behind) a portal; falls back to inside it. */
    static Location exitOf(Block bottom) {
        World w = bottom.getWorld();
        int x = bottom.getX(), y = bottom.getY(), z = bottom.getZ();
        boolean ax = axisX(w, x, y, z);
        int[][] sides = ax ? new int[][]{{0, 1, 0}, {0, -1, 180}} : new int[][]{{1, 0, 270}, {-1, 0, 90}};
        // same height as the portal floor first, then one lower (frame standing on the ground)
        for (int ey = y; ey >= y - 1; ey--) {
            for (int[] s : sides) {
                int sx = x + s[0], sz = z + s[1];
                if (ground(w.getBlockTypeIdAt(sx, ey - 1, sz))
                        && passable(w.getBlockTypeIdAt(sx, ey, sz))
                        && passable(w.getBlockTypeIdAt(sx, ey + 1, sz))) {
                    return new Location(w, sx + 0.5, ey, sz + 0.5, s[2], 0);
                }
            }
        }
        return new Location(w, x + 0.5, y, z + 0.5);
    }

    /** Blocks a return portal may replace: air, leaves, tall grass, dead bush, flowers, snow layer. */
    static boolean free(int id) {
        return id == AIR || id == LEAVES || id == 31 || id == 32 || id == 37 || id == 38 || id == 78;
    }

    /** Natural ground the bottom frame row may replace (like vanilla, but never player-made blocks). */
    static boolean natural(int id) {
        switch (id) {
            case 1: case 2: case 3: case 12: case 13: case 14: case 15: case 16: case 21: case 24:
            case 56: case 73: case 74: case 79: case 80: case 82: case 87: case 88:
                return true;
            default:
                return false;
        }
    }

    private static void loadArea(World w, int cx, int cz, int radius) {
        for (int chx = (cx - radius) >> 4; chx <= (cx + radius) >> 4; chx++) {
            for (int chz = (cz - radius) >> 4; chz <= (cz + radius) >> 4; chz++) {
                w.loadChunk(chx, chz);
            }
        }
    }

    /**
     * Does a 4x5 frame fit at ground level like vanilla: walking height g, the bottom frame
     * row replaces the natural ground at g - 1, so the opening is at walking height.
     * x0/z0 is the first frame column, ax = frame runs along X.
     * clearance: also need a standable row in front of and behind it (vanilla's first pass).
     */
    private static boolean fitsOnGround(World w, int x0, int g, int z0, boolean ax, boolean clearance) {
        if (g < 2 || g + 3 > 127) return false;
        int dx = ax ? 1 : 0, dz = ax ? 0 : 1;
        int nx = ax ? 0 : 1, nz = ax ? 1 : 0;
        for (int i = 0; i < 4; i++) {
            int x = x0 + dx * i, z = z0 + dz * i;
            if (!natural(w.getBlockTypeIdAt(x, g - 1, z))) return false;
            for (int y = g; y <= g + 3; y++) {
                if (!free(w.getBlockTypeIdAt(x, y, z))) return false;
            }
            if (!clearance) continue;
            for (int side = -1; side <= 1; side += 2) {
                int fx = x + nx * side, fz = z + nz * side;
                if (!ground(w.getBlockTypeIdAt(fx, g - 1, fz))) return false;
                for (int y = g; y <= g + 2; y++) {
                    if (!free(w.getBlockTypeIdAt(fx, y, fz))) return false;
                }
            }
        }
        return true;
    }

    /** Does a floating frame with a platform in front and behind fit into free space at g? */
    private static boolean fitsInAir(World w, int x0, int g, int z0, boolean ax) {
        if (g < 2 || g + 4 > 127) return false;
        int dx = ax ? 1 : 0, dz = ax ? 0 : 1;
        int nx = ax ? 0 : 1, nz = ax ? 1 : 0;
        for (int i = 0; i < 4; i++) {
            int x = x0 + dx * i, z = z0 + dz * i;
            for (int y = g; y <= g + 4; y++) {
                if (!free(w.getBlockTypeIdAt(x, y, z))) return false;
            }
            for (int side = -1; side <= 1; side += 2) {
                int fx = x + nx * side, fz = z + nz * side;
                for (int y = g - 1; y <= g + 2; y++) {
                    if (!free(w.getBlockTypeIdAt(fx, y, fz))) return false;
                }
            }
        }
        return true;
    }

    /**
     * Places the frame with its bottom row at b and lights it. Only ever replaces blocks
     * that free() allows, plus natural ground under the frame for ground-level portals.
     */
    private static Block place(World w, int x0, int b, int z0, boolean ax, boolean platform) {
        int dx = ax ? 1 : 0, dz = ax ? 0 : 1;
        int nx = ax ? 0 : 1, nz = ax ? 1 : 0;
        int stand = platform ? b : b + 1; // feet height in front of the portal
        List<Block> inside = new ArrayList<Block>();
        for (int i = 0; i < 4; i++) {
            int x = x0 + dx * i, z = z0 + dz * i;
            for (int y = b; y <= b + 4; y++) {
                boolean edge = i == 0 || i == 3 || y == b || y == b + 4;
                Block c = w.getBlockAt(x, y, z);
                if (edge) c.setTypeIdAndData(GLOWSTONE, (byte) 0, false);
                else inside.add(c);
            }
            for (int side = -1; side <= 1; side += 2) {
                int fx = x + nx * side, fz = z + nz * side;
                if (platform) {
                    Block floor = w.getBlockAt(fx, b - 1, fz);
                    if (free(floor.getTypeId())) floor.setTypeIdAndData(COBBLESTONE, (byte) 0, false);
                }
                // leaves would block the way out
                for (int y = stand; y <= stand + 2; y++) {
                    Block c = w.getBlockAt(fx, y, fz);
                    if (c.getTypeId() == LEAVES) c.setTypeIdAndData(AIR, (byte) 0, false);
                }
            }
        }
        light(inside);
        return w.getBlockAt(x0 + dx, b + 1, z0 + dz); // bottom interior cell
    }

    /**
     * Vanilla-like return portal placement (b1.7.3 PortalTravelAgent): search a free spot on
     * the ground around cx/cz, nearest first; first with room in front and behind, then without.
     * Returns the bottom interior cell, or null if nothing fits.
     */
    static Block buildNear(World w, int cx, int cz, int radius) {
        loadArea(w, cx, cz, radius + 5);
        for (int pass = 0; pass < 2; pass++) {
            boolean clearance = pass == 0;
            for (int r = 0; r <= radius; r++) {
                for (int ox = -r; ox <= r; ox++) {
                    for (int oz = -r; oz <= r; oz++) {
                        if (Math.abs(ox) != r && Math.abs(oz) != r) continue;
                        int x = cx + ox, z = cz + oz;
                        for (int g = 124; g >= 2; g--) { // top-down: surface before caves
                            if (!free(w.getBlockTypeIdAt(x, g, z))) continue;
                            if (!natural(w.getBlockTypeIdAt(x, g - 1, z))) continue;
                            if (fitsOnGround(w, x, g, z, true, clearance)) return place(w, x, g - 1, z, true, false);
                            if (fitsOnGround(w, x, g, z, false, clearance)) return place(w, x, g - 1, z, false, false);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Fallback like vanilla, but never carving into terrain: a floating portal on a small
     * platform, starting at a height between 70 and 118 and moving until there is room.
     */
    static Block buildFloating(World w, int cx, int cz, int startY) {
        loadArea(w, cx, cz, 5);
        int y0 = Math.max(70, Math.min(118, startY));
        for (int g = y0; g <= 123; g++) {
            if (fitsInAir(w, cx, g, cz, true)) return place(w, cx, g, cz, true, true);
            if (fitsInAir(w, cx, g, cz, false)) return place(w, cx, g, cz, false, true);
        }
        for (int g = y0 - 1; g >= 2; g--) {
            if (fitsInAir(w, cx, g, cz, true)) return place(w, cx, g, cz, true, true);
            if (fitsInAir(w, cx, g, cz, false)) return place(w, cx, g, cz, false, true);
        }
        return null;
    }
}
