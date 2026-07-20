package re.imc.geysermodelengineextension.managers.resourcepack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;

import re.imc.geysermodelengineextension.util.ZipUtil;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackValidatorTest {

    @TempDir
    Path temp;

    @Test
    void acceptsClosedPackAndMatchingArchive() throws Exception {
        Path pack = createValidPack(temp.resolve("pack"));

        ResourcePackValidator.ValidationReport report = ResourcePackValidator.validate(pack);
        assertTrue(report.isValid(), report.errors().toString());
        assertEquals(1, report.entities());
        assertEquals(1, report.geometries());
        assertEquals(1, report.animations());
        assertEquals(1, report.animationControllers());
        assertEquals(1, report.renderControllers());
        assertEquals(1, report.textures());
        assertEquals(0, report.remainingMythicCommands());

        Path zip = temp.resolve("pack.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            ZipUtil.compressFolder(pack.toFile(), null, output);
        }
        assertDoesNotThrow(() -> ResourcePackValidator.validateArchiveMatches(pack, zip));
    }

    @Test
    void rejectsResidualMythicCommand() throws Exception {
        Path pack = createValidPack(temp.resolve("pack"));
        Files.writeString(pack.resolve("animations/test.json"), """
                {"format_version":"1.8.0","animations":{"animation.test.idle":{
                  "timeline":{"0.1":"mm:server_skill;"}}}}
                """, StandardCharsets.UTF_8);

        ResourcePackValidator.ValidationReport report = ResourcePackValidator.validate(pack);
        assertFalse(report.isValid());
        assertEquals(1, report.remainingMythicCommands());
        assertThrows(IllegalStateException.class, report::throwIfInvalid);
    }

    @Test
    void rejectsMythicCommandInJsonKey() throws Exception {
        Path pack = createValidPack(temp.resolve("pack"));
        Files.writeString(pack.resolve("animations/test.json"), """
                {"format_version":"1.8.0","animations":{"mm:server_skill;":{"loop":true}}}
                """, StandardCharsets.UTF_8);

        ResourcePackValidator.ValidationReport report = ResourcePackValidator.validate(pack);
        assertFalse(report.isValid());
        assertEquals(1, report.remainingMythicCommands());
    }

    @Test
    void rejectsBrokenEntityReference() throws Exception {
        Path pack = createValidPack(temp.resolve("pack"));
        String entity = Files.readString(pack.resolve("entity/test.json"));
        Files.writeString(pack.resolve("entity/test.json"),
                entity.replace("geometry.test", "geometry.missing"), StandardCharsets.UTF_8);

        ResourcePackValidator.ValidationReport report = ResourcePackValidator.validate(pack);
        assertFalse(report.isValid());
        assertTrue(report.errors().stream().anyMatch(error -> error.contains("missing geometry")));
    }

    @Test
    void keepsUnusedEmissiveTextureAsWarning() throws Exception {
        Path pack = createValidPack(temp.resolve("pack"));
        Files.write(pack.resolve("textures/entity/test_e.png"), new byte[]{4, 5, 6});

        ResourcePackValidator.ValidationReport report = ResourcePackValidator.validate(pack);
        assertTrue(report.isValid(), report.errors().toString());
        assertTrue(report.warnings().stream().anyMatch(warning -> warning.contains("unreferenced texture")));
    }

    @Test
    void rejectsArchiveThatDoesNotMatchDirectory() throws Exception {
        Path pack = createValidPack(temp.resolve("pack"));
        Path zip = temp.resolve("pack.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            ZipUtil.compressFolder(pack.toFile(), null, output);
        }
        Files.writeString(pack.resolve("extra.txt"), "late change");

        assertThrows(Exception.class, () -> ResourcePackValidator.validateArchiveMatches(pack, zip));
    }

    private static Path createValidPack(Path pack) throws Exception {
        Files.createDirectories(pack.resolve("entity"));
        Files.createDirectories(pack.resolve("models/entity"));
        Files.createDirectories(pack.resolve("animations"));
        Files.createDirectories(pack.resolve("animation_controllers"));
        Files.createDirectories(pack.resolve("render_controllers"));
        Files.createDirectories(pack.resolve("textures/entity"));

        write(pack.resolve("manifest.json"), """
                {"format_version":2,
                 "header":{"uuid":"a8b386e1-ed2e-4997-ad10-4992e283e676","version":[0,0,1]},
                 "modules":[{"type":"resources","uuid":"8b6803fd-1bcc-4d08-9890-ecbb96967913","version":[0,0,1]}]}
                """);
        write(pack.resolve("models/entity/test.json"), """
                {"format_version":"1.12.0","minecraft:geometry":[{
                  "description":{"identifier":"geometry.test","texture_width":16,"texture_height":16},
                  "bones":[]}]}
                """);
        write(pack.resolve("animations/test.json"), """
                {"format_version":"1.8.0","animations":{"animation.test.idle":{"loop":true}}}
                """);
        write(pack.resolve("animation_controllers/test.json"), """
                {"format_version":"1.10.0","animation_controllers":{
                  "controller.animation.test.idle":{"states":{"play":{"animations":["idle"]}}}}}
                """);
        write(pack.resolve("render_controllers/test.json"), """
                {"format_version":"1.8.0","render_controllers":{
                  "controller.render.test":{"geometry":"Geometry.default","textures":["Texture.default"]}}}
                """);
        write(pack.resolve("entity/test.json"), """
                {"format_version":"1.10.0","minecraft:client_entity":{"description":{
                  "identifier":"modelengine:test",
                  "textures":{"default":"textures/entity/test"},
                  "geometry":{"default":"geometry.test"},
                  "animations":{"idle":"animation.test.idle","idle_control":"controller.animation.test.idle"},
                  "scripts":{"animate":["idle_control"]},
                  "render_controllers":["controller.render.test"]}}}
                """);
        Files.write(pack.resolve("textures/entity/test.png"), new byte[]{1, 2, 3});
        return pack;
    }

    private static void write(Path path, String text) throws Exception {
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
