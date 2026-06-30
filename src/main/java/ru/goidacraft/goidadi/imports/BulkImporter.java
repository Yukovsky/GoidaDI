package ru.goidacraft.goidadi.imports;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.goidacraft.goidadi.Config;
import ru.goidacraft.goidadi.compat.DcIntegrationCompat;
import ru.goidacraft.goidadi.compat.NameResolver;
import ru.goidacraft.goidadi.data.LinkDatabase;
import ru.goidacraft.goidadi.data.LinkRecord;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Bulk pre-seeding and export of MC&harr;Discord links. Import accepts CSV
 * ({@code mcName,discordId[,discordName]}) or a JSON array of objects with the same fields. Names are
 * resolved to UUIDs through {@link NameResolver} (GoidaAuth-aware). Import is idempotent: existing
 * identical links are skipped, changed ones updated, and Discord IDs already taken by another account
 * are reported as errors instead of silently stolen.
 */
public final class BulkImporter {
    private static final Logger LOG = LoggerFactory.getLogger(BulkImporter.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private BulkImporter() {}

    public record Report(int added, int updated, int skipped, List<String> errors) {}

    private record RawEntry(String mcName, String discordId, String discordName) {}

    private record Resolved(UUID uuid, String mcName, String discordId, String discordName) {}

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get().resolve("goidadi");
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    public static CompletableFuture<Report> importFile(MinecraftServer server, LinkDatabase db, Path file) {
        List<String> errors = new ArrayList<>();
        List<RawEntry> raw;
        try {
            raw = parse(file, errors);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        // resolve every name to a UUID (and a Discord display name if missing), then write once
        List<CompletableFuture<Resolved>> futures = new ArrayList<>();
        for (RawEntry r : raw) {
            if (r.discordId() == null || r.discordId().isBlank()) {
                errors.add("Пропуск (нет Discord ID): " + r.mcName());
                continue;
            }
            futures.add(NameResolver.resolve(server, r.mcName()).thenApply(uuid -> {
                String dn = r.discordName();
                if (dn == null || dn.isBlank()) dn = DcIntegrationCompat.resolveDiscordName(r.discordId());
                return new Resolved(uuid, r.mcName(), r.discordId(), dn);
            }));
        }

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenCompose(v -> {
            List<Resolved> resolved = new ArrayList<>();
            for (CompletableFuture<Resolved> f : futures) {
                Resolved r = f.join();
                if (r != null) resolved.add(r);
            }
            return db.compute(c -> writeAll(c, resolved, errors));
        }).thenApply(report -> {
            // best-effort mirror after a successful write
            if (Config.MIRROR_TO_DC_INTEGRATION.get()) {
                for (RawEntry r : raw) {
                    if (r.discordId() != null && !r.discordId().isBlank()) {
                        NameResolver.resolve(server, r.mcName())
                                .thenAccept(uuid -> DcIntegrationCompat.mirrorLink(r.discordId(), uuid));
                    }
                }
            }
            return report;
        });
    }

    private static Report writeAll(java.sql.Connection c, List<Resolved> resolved, List<String> errors)
            throws java.sql.SQLException {
        int added = 0, updated = 0, skipped = 0;
        Instant now = Instant.now();
        Instant deadline = now.plus(Config.GRACE_DAYS.get(), ChronoUnit.DAYS);

        for (Resolved r : resolved) {
            Optional<LinkRecord> byDiscord = LinkDatabase.findByDiscordIdSync(c, r.discordId());
            if (byDiscord.isPresent() && !byDiscord.get().mcUuid().equals(r.uuid())) {
                errors.add("Конфликт: Discord " + r.discordId() + " уже привязан к " + byDiscord.get().mcName());
                skipped++;
                continue;
            }
            Optional<LinkRecord> existing = LinkDatabase.findByUuidSync(c, r.uuid());
            if (existing.isPresent() && r.discordId().equals(existing.get().discordId())) {
                skipped++;
                continue;
            }
            Instant fs = existing.map(LinkRecord::firstSeen).orElse(now);
            Instant dl = existing.map(LinkRecord::deadline).orElse(deadline);
            LinkDatabase.setLinkSync(c, r.uuid(), r.mcName(), r.discordId(), r.discordName(), fs, dl);
            if (existing.isPresent() && existing.get().isLinked()) updated++;
            else added++;
        }
        return new Report(added, updated, skipped, errors);
    }

    private static List<RawEntry> parse(Path file, List<String> errors) throws IOException {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".json")) return parseJson(file);
        return parseCsv(file, errors);
    }

    private static List<RawEntry> parseCsv(Path file, List<String> errors) throws IOException {
        List<RawEntry> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (i == 0 && line.toLowerCase().startsWith("mcname")) continue; // header
            String[] parts = line.split(",");
            if (parts.length < 2) {
                errors.add("Строка " + (i + 1) + ": неверный формат");
                continue;
            }
            String mc = parts[0].trim();
            String id = parts[1].trim();
            String dn = parts.length > 2 ? parts[2].trim() : null;
            out.add(new RawEntry(mc, id, dn));
        }
        return out;
    }

    private static List<RawEntry> parseJson(Path file) throws IOException {
        List<RawEntry> out = new ArrayList<>();
        try (Reader r = Files.newBufferedReader(file)) {
            JsonElement root = JsonParser.parseReader(r);
            if (root.isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray()) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    String mc = str(o, "mcName");
                    String id = str(o, "discordId");
                    String dn = str(o, "discordName");
                    if (mc != null && id != null) out.add(new RawEntry(mc, id, dn));
                }
            }
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    public static CompletableFuture<Path> export(LinkDatabase db, String format) {
        boolean json = "json".equalsIgnoreCase(format);
        return db.listAll().thenApply(records -> {
            try {
                Path dir = configDir();
                Files.createDirectories(dir);
                Path file = dir.resolve("export." + (json ? "json" : "csv"));
                if (json) writeJson(file, records);
                else writeCsv(file, records);
                return file;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void writeCsv(Path file, List<LinkRecord> records) throws IOException {
        try (Writer w = Files.newBufferedWriter(file)) {
            w.write("mc_uuid,mc_name,discord_id,discord_name,first_seen,deadline,linked_at\n");
            for (LinkRecord r : records) {
                w.write(String.join(",",
                        nz(r.mcUuid().toString()), nz(r.mcName()), nz(r.discordId()), nz(r.discordName()),
                        nz(String.valueOf(r.firstSeen())), nz(String.valueOf(r.deadline())),
                        nz(r.linkedAt() == null ? "" : r.linkedAt().toString())));
                w.write("\n");
            }
        }
    }

    private static void writeJson(Path file, List<LinkRecord> records) throws IOException {
        JsonArray arr = new JsonArray();
        for (LinkRecord r : records) {
            JsonObject o = new JsonObject();
            o.addProperty("mcUuid", r.mcUuid().toString());
            o.addProperty("mcName", r.mcName());
            o.addProperty("discordId", r.discordId());
            o.addProperty("discordName", r.discordName());
            o.addProperty("firstSeen", String.valueOf(r.firstSeen()));
            o.addProperty("deadline", String.valueOf(r.deadline()));
            o.addProperty("linkedAt", r.linkedAt() == null ? null : r.linkedAt().toString());
            arr.add(o);
        }
        try (Writer w = Files.newBufferedWriter(file)) {
            GSON.toJson(arr, w);
        }
    }

    private static String nz(String s) {
        if (s == null) return "";
        return s.contains(",") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
