package de.weltkarte.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/** Eine Markierung auf der Karte (Pin, Stadt, Gebäude, Wegstein). */
public class Marker {
    public String id = UUID.randomUUID().toString().substring(0, 8);
    public String name = "Unbenannt";
    public String description = "";
    public MarkerType type = MarkerType.ORT;
    public String world;
    public double x;
    public Double y = null; // null = Höhe automatisch bestimmen
    public double z;
    public String color = "#e53935";
    public String owner = "";
    /** Optionale zweite Ecke – dann ist die Markierung eine Fläche (z. B. ein Gebäude). */
    public Integer x2;
    public Integer z2;
    public long created = System.currentTimeMillis();

    public boolean isArea() { return x2 != null && z2 != null; }

    public boolean hasY() { return y != null; }

    public int blockX() { return (int) Math.floor(x); }
    public int blockY() { return (int) Math.floor(y == null ? 64 : y); }
    public int blockZ() { return (int) Math.floor(z); }

    public double centerX() { return isArea() ? (x + x2) / 2.0 : x; }
    public double centerZ() { return isArea() ? (z + z2) / 2.0 : z; }

    public Location location() {
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        double yy = hasY() ? y : w.getHighestBlockYAt((int) Math.floor(centerX()), (int) Math.floor(centerZ())) + 1;
        return new Location(w, centerX() + 0.5, yy, centerZ() + 0.5);
    }

    public double distanceSq2D(double px, double pz) {
        double dx = centerX() - px, dz = centerZ() - pz;
        return dx * dx + dz * dz;
    }

    public Marker copy() {
        Marker m = new Marker();
        m.id = id; m.name = name; m.description = description; m.type = type; m.world = world;
        m.x = x; m.y = y; m.z = z; m.color = color; m.owner = owner; m.x2 = x2; m.z2 = z2; m.created = created;
        return m;
    }

    public boolean sameBlock(String world, int bx, int by, int bz) {
        return this.world != null && this.world.equals(world) && hasY()
                && blockX() == bx && blockY() == by && blockZ() == bz;
    }

    public int rgb() {
        try {
            String c = color.startsWith("#") ? color.substring(1) : color;
            return Integer.parseInt(c, 16) & 0xFFFFFF;
        } catch (Exception e) {
            return 0xE53935;
        }
    }
}
