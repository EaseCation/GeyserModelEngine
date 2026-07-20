package re.imc.geysermodelengineextension.managers.resourcepack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackManifestManagerTest {

    @TempDir
    Path temp;

    @Test
    void createsNewIdentityAtTemplateVersion() throws Exception {
        PackManifestManager.ManifestBuild build = PackManifestManager.prepare(
                template(), temp.resolve("missing"), temp.resolve("missing.zip"), true);

        assertFalse(build.reusedIdentity());
        assertFalse(build.versionIncremented());
        assertEquals(List.of(0, 0, 1), build.version());
        JsonObject manifest = build.manifest();
        UUID.fromString(manifest.getAsJsonObject("header").get("uuid").getAsString());
        UUID.fromString(manifest.getAsJsonArray("modules").get(0).getAsJsonObject().get("uuid").getAsString());
        assertNotEquals("%uuid-1%", manifest.getAsJsonObject("header").get("uuid").getAsString());
    }

    @Test
    void preservesIdentityAndVersionWhenContentIsUnchanged() throws Exception {
        Path current = temp.resolve("pack");
        Files.createDirectories(current);
        JsonObject existing = existingManifest();
        Files.writeString(current.resolve("manifest.json"), existing.toString(), StandardCharsets.UTF_8);

        PackManifestManager.ManifestBuild build = PackManifestManager.prepare(
                template(), current, temp.resolve("pack.zip"), false);

        assertTrue(build.reusedIdentity());
        assertFalse(build.versionIncremented());
        assertEquals(List.of(0, 0, 7), build.version());
        assertEquals(existing.getAsJsonObject("header").get("uuid"),
                build.manifest().getAsJsonObject("header").get("uuid"));
    }

    @Test
    void incrementsHeaderAndModuleTogetherWhenContentChanges() throws Exception {
        Path current = temp.resolve("pack");
        Files.createDirectories(current);
        Files.writeString(current.resolve("manifest.json"), existingManifest().toString(), StandardCharsets.UTF_8);

        PackManifestManager.ManifestBuild build = PackManifestManager.prepare(
                template(), current, temp.resolve("pack.zip"), true);

        assertEquals(List.of(0, 0, 8), build.version());
        assertEquals(build.manifest().getAsJsonObject("header").get("version"),
                build.manifest().getAsJsonArray("modules").get(0).getAsJsonObject().get("version"));
    }

    @Test
    void recoversExistingIdentityFromZipWhenDirectoryIsMissing() throws Exception {
        Path zip = temp.resolve("pack.zip");
        JsonObject existing = existingManifest();
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("manifest.json"));
            output.write(existing.toString().getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        PackManifestManager.ManifestBuild build = PackManifestManager.prepare(
                template(), temp.resolve("missing"), zip, true);

        assertTrue(build.reusedIdentity());
        assertEquals(List.of(0, 0, 8), build.version());
        assertEquals(existing.getAsJsonObject("header").get("uuid"),
                build.manifest().getAsJsonObject("header").get("uuid"));
    }

    @Test
    void rejectsUnsynchronizedExistingVersions() throws Exception {
        Path current = temp.resolve("pack");
        Files.createDirectories(current);
        JsonObject existing = existingManifest();
        existing.getAsJsonArray("modules").get(0).getAsJsonObject()
                .add("version", JsonParser.parseString("[0,0,6]"));
        Files.writeString(current.resolve("manifest.json"), existing.toString(), StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> PackManifestManager.prepare(
                template(), current, temp.resolve("pack.zip"), true));
    }

    @Test
    void rejectsReusedHeaderAndModuleUuid() throws Exception {
        JsonObject manifest = existingManifest();
        manifest.getAsJsonArray("modules").get(0).getAsJsonObject().addProperty(
                "uuid", manifest.getAsJsonObject("header").get("uuid").getAsString());

        assertThrows(IllegalArgumentException.class,
                () -> PackManifestManager.validateAndReadVersion(manifest));
    }

    private static JsonObject template() {
        return JsonParser.parseString("""
                {"format_version":2,"header":{"uuid":"%uuid-1%","version":[0,0,1]},
                 "modules":[{"type":"resources","uuid":"%uuid-2%","version":[0,0,1]}]}
                """).getAsJsonObject();
    }

    private static JsonObject existingManifest() {
        return JsonParser.parseString("""
                {"format_version":2,
                 "header":{"uuid":"a8b386e1-ed2e-4997-ad10-4992e283e676","version":[0,0,7]},
                 "modules":[{"type":"resources","uuid":"8b6803fd-1bcc-4d08-9890-ecbb96967913","version":[0,0,7]}]}
                """).getAsJsonObject();
    }
}
