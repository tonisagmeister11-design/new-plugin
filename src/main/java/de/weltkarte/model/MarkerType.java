package de.weltkarte.model;

import org.bukkit.Material;

public enum MarkerType {
    ORT("Ort", Material.RED_BANNER),
    STADT("Stadt", Material.BELL),
    GEBAEUDE("Gebäude", Material.BRICKS),
    WEGSTEIN("Wegstein", Material.LODESTONE);

    private final String displayName;
    private final Material icon;

    MarkerType(String displayName, Material icon) {
        this.displayName = displayName;
        this.icon = icon;
    }

    public String displayName() { return displayName; }
    public Material icon() { return icon; }

    public static MarkerType parse(String s) {
        if (s == null) return null;
        for (MarkerType t : values()) {
            if (t.name().equalsIgnoreCase(s) || t.displayName.equalsIgnoreCase(s)) return t;
        }
        if (s.equalsIgnoreCase("gebaude") || s.equalsIgnoreCase("haus")) return GEBAEUDE;
        if (s.equalsIgnoreCase("pin")) return ORT;
        return null;
    }
}
