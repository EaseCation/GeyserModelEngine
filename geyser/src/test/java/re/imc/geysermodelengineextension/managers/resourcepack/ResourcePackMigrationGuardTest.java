package re.imc.geysermodelengineextension.managers.resourcepack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackMigrationGuardTest {

    @TempDir
    Path temp;

    @Test
    void acceptsOnlyTimelineRemoval() throws Exception {
        Path current = temp.resolve("current");
        Path candidate = temp.resolve("candidate");
        Files.createDirectories(current);
        Files.createDirectories(candidate);
        Files.writeString(current.resolve("animation.json"), """
                {"animations":{"attack":{"animation_length":1,"bones":{"root":{"rotation":[0,1,2]}},
                "timeline":{"0.5":"mm:damage;"}}}}
                """);
        Files.writeString(candidate.resolve("animation.json"), """
                {"animations":{"attack":{"animation_length":1,"bones":{"root":{"rotation":[0,1,2]}}}}}
                """);

        ResourcePackMigrationGuard.MigrationCheck check =
                ResourcePackMigrationGuard.verify(current, candidate);
        assertTrue(check.applied());
        assertEquals(1, check.removedCommands());
    }

    @Test
    void rejectsBoneOrControllerChangesDuringMigration() throws Exception {
        Path current = temp.resolve("current");
        Path candidate = temp.resolve("candidate");
        Files.createDirectories(current);
        Files.createDirectories(candidate);
        Files.writeString(current.resolve("animation.json"), """
                {"animations":{"attack":{"bones":{"root":{"rotation":[0,1,2]}},
                "timeline":{"0.5":"mm:damage;"}}}}
                """);
        Files.writeString(candidate.resolve("animation.json"), """
                {"animations":{"attack":{"bones":{"root":{"rotation":[0,9,2]}}}}}
                """);

        assertThrows(IllegalStateException.class,
                () -> ResourcePackMigrationGuard.verify(current, candidate));
    }

    @Test
    void isInactiveAfterMigration() throws Exception {
        Path current = temp.resolve("current");
        Path candidate = temp.resolve("candidate");
        Files.createDirectories(current);
        Files.createDirectories(candidate);
        Files.writeString(current.resolve("content.json"), "{\"value\":1}");
        Files.writeString(candidate.resolve("content.json"), "{\"value\":2}");

        assertFalse(ResourcePackMigrationGuard.verify(current, candidate).applied());
    }
}
