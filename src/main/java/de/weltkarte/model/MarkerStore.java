package de.weltkarte.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Logger;

/**
 * Speichert den Entwurf (nur Admins sehen/bearbeiten ihn) und die veröffentlichte Karte,
 * die alle Spieler sehen. "Karte updaten" kopiert den Entwurf in die veröffentlichte Version.
 */
public class MarkerStore {

    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static class Data {
        List<Marker> draft = new ArrayList<>();
        List<Marker> published = new ArrayList<>();
        long publishedAt = 0;
        int version = 0;
        Map<String, Set<String>> discovered = new HashMap<>();
    }

    private final File file;
    private final Logger log;
    private Data data = new Data();
    private volatile List<Marker> publishedView = List.of();

    public MarkerStore(File file, Logger log) {
        this.file = file;
        this.log = log;
        load();
    }

    private synchronized void load() {
        if (!file.exists()) { refreshView(); return; }
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Data d = GSON.fromJson(r, Data.class);
            if (d != null) data = d;
            if (data.draft == null) data.draft = new ArrayList<>();
            if (data.published == null) data.published = new ArrayList<>();
            if (data.discovered == null) data.discovered = new HashMap<>();
            data.draft.removeIf(m -> m == null || m.type == null || m.world == null);
            data.published.removeIf(m -> m == null || m.type == null || m.world == null);
        } catch (Exception e) {
            log.severe("Konnte markers.json nicht laden: " + e.getMessage());
        }
        refreshView();
    }

    public synchronized void save() {
        try {
            file.getParentFile().mkdirs();
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.severe("Konnte markers.json nicht speichern: " + e.getMessage());
        }
    }

    private void refreshView() {
        List<Marker> copy = new ArrayList<>();
        for (Marker m : data.published) copy.add(m.copy());
        publishedView = Collections.unmodifiableList(copy);
    }

    /** Veröffentlichte Markierungen (thread-sicher, unveränderlich). */
    public List<Marker> published() { return publishedView; }

    public synchronized List<Marker> draft() {
        List<Marker> copy = new ArrayList<>();
        for (Marker m : data.draft) copy.add(m.copy());
        return copy;
    }

    public synchronized void setDraft(List<Marker> markers) {
        data.draft = new ArrayList<>();
        for (Marker m : markers) data.draft.add(m.copy());
        save();
    }

    public synchronized void addDraft(Marker m) {
        data.draft.add(m.copy());
        save();
    }

    public synchronized void updateDraft(Marker m) {
        for (int i = 0; i < data.draft.size(); i++) {
            if (data.draft.get(i).id.equals(m.id)) { data.draft.set(i, m.copy()); save(); return; }
        }
        addDraft(m);
    }

    public synchronized boolean removeDraft(String id) {
        boolean r = data.draft.removeIf(m -> m.id.equals(id));
        if (r) save();
        return r;
    }

    public synchronized Marker findDraft(String id) {
        for (Marker m : data.draft) if (m.id.equals(id)) return m.copy();
        return null;
    }

    /** Anzahl der Unterschiede zwischen Entwurf und Live-Karte. */
    public synchronized int pendingChanges() {
        Map<String, String> live = new HashMap<>();
        for (Marker m : data.published) live.put(m.id, GSON.toJson(m));
        int changes = 0;
        Set<String> seen = new HashSet<>();
        for (Marker m : data.draft) {
            seen.add(m.id);
            String l = live.get(m.id);
            if (l == null || !l.equals(GSON.toJson(m))) changes++;
        }
        for (String id : live.keySet()) if (!seen.contains(id)) changes++;
        return changes;
    }

    public record PublishResult(List<Marker> addedWaystones, List<Marker> removedWaystones, int total, int version) {}

    /** Veröffentlicht den Entwurf – ab jetzt sehen ihn alle Spieler. */
    public synchronized PublishResult publish() {
        Map<String, Marker> oldWs = new HashMap<>();
        for (Marker m : data.published) if (m.type == MarkerType.WEGSTEIN) oldWs.put(m.id, m);
        List<Marker> added = new ArrayList<>();
        Set<String> stillThere = new HashSet<>();
        for (Marker m : data.draft) {
            if (m.type != MarkerType.WEGSTEIN) continue;
            Marker old = oldWs.get(m.id);
            stillThere.add(m.id);
            if (old == null || old.blockX() != m.blockX() || old.blockZ() != m.blockZ()
                    || old.hasY() != m.hasY() || (m.hasY() && old.blockY() != m.blockY())
                    || !Objects.equals(old.world, m.world)) {
                added.add(m.copy());
                if (old != null) stillThere.remove(m.id);
            }
        }
        List<Marker> removed = new ArrayList<>();
        for (Marker m : oldWs.values()) {
            boolean moved = added.stream().anyMatch(a -> a.id.equals(m.id));
            if (!stillThere.contains(m.id) || moved) removed.add(m.copy());
        }
        data.published = new ArrayList<>();
        for (Marker m : data.draft) data.published.add(m.copy());
        data.publishedAt = System.currentTimeMillis();
        data.version++;
        refreshView();
        save();
        return new PublishResult(added, removed, data.published.size(), data.version);
    }

    /** Setzt eine nachträglich bestimmte Höhe (z. B. für im Web gesetzte Wegsteine). */
    public synchronized void fixY(String id, double y) {
        for (Marker m : data.draft) if (m.id.equals(id)) m.y = y;
        for (Marker m : data.published) if (m.id.equals(id)) m.y = y;
        refreshView();
        save();
    }

    public synchronized long publishedAt() { return data.publishedAt; }
    public synchronized int version() { return data.version; }

    // ---- Wegstein-Entdeckungen ----
    public synchronized boolean discover(UUID player, String markerId) {
        boolean added = data.discovered.computeIfAbsent(player.toString(), k -> new HashSet<>()).add(markerId);
        if (added) save();
        return added;
    }

    public synchronized boolean hasDiscovered(UUID player, String markerId) {
        Set<String> s = data.discovered.get(player.toString());
        return s != null && s.contains(markerId);
    }

    public static List<Marker> parseList(String json) {
        return GSON.fromJson(json, new TypeToken<List<Marker>>() {}.getType());
    }
}
