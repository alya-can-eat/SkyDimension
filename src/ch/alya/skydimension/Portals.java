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
        for (int[] s : sides) {
            int sx = x + s[0], sz = z + s[1];
            if (ground(w.getBlockTypeIdAt(sx, y - 1, sz))
                    && passable(w.getBlockTypeIdAt(sx, y, sz))
                    && passable(w.getBlockTypeIdAt(sx, y + 1, sz))) {
                return new Location(w, sx + 0.5, y, sz + 0.5, s[2], 0);
            }
        }
        return new Location(w, x + 0.5, y, z + 0.5);
    }

    /**
     * Builds a 2x3 glowstone portal (along X) whose interior starts at the given feet position,
     * with a small floor in front and behind. Returns the exit spot in front of it.
     */
    static Location build(World w, int sx, int sy, int sz) {
        if (sy < 2) sy = 2;
        if (sy > 123) sy = 123;

        // floor and headroom on both sides
        for (int x = sx - 1; x <= sx + 2; x++) {
            for (int dz = -1; dz <= 1; dz += 2) {
                Block floor = w.getBlockAt(x, sy - 1, sz + dz);
                if (!ground(floor.getTypeId())) floor.setTypeIdAndData(COBBLESTONE, (byte) 0, false);
                for (int y = sy; y <= sy + 2; y++) {
                    Block b = w.getBlockAt(x, y, sz + dz);
                    if (!passable(b.getTypeId())) b.setTypeIdAndData(AIR, (byte) 0, false);
                }
            }
        }
        // frame
        for (int x = sx - 1; x <= sx + 2; x++) {
            w.getBlockAt(x, sy - 1, sz).setTypeIdAndData(GLOWSTONE, (byte) 0, false);
            w.getBlockAt(x, sy + 3, sz).setTypeIdAndData(GLOWSTONE, (byte) 0, false);
        }
        for (int y = sy; y <= sy + 2; y++) {
            w.getBlockAt(sx - 1, y, sz).setTypeIdAndData(GLOWSTONE, (byte) 0, false);
            w.getBlockAt(sx + 2, y, sz).setTypeIdAndData(GLOWSTONE, (byte) 0, false);
        }
        // interior
        List<Block> inside = new ArrayList<Block>();
        for (int x = sx; x <= sx + 1; x++) {
            for (int y = sy; y <= sy + 2; y++) inside.add(w.getBlockAt(x, y, sz));
        }
        light(inside);
        return new Location(w, sx + 1.0, sy, sz + 1.5, 0, 0);
    }
}
