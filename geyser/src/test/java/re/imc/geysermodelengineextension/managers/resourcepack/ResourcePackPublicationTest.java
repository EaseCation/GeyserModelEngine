package re.imc.geysermodelengineextension.managers.resourcepack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackPublicationTest {

    @TempDir
    Path temp;

    @Test
    void comparisonIgnoresOnlyManifest() throws Exception {
        Path current = temp.resolve("current");
        Path candidate = temp.resolve("candidate");
        Files.createDirectories(current);
        Files.createDirectories(candidate);
        Files.writeString(current.resolve("manifest.json"), "old");
        Files.writeString(candidate.resolve("manifest.json"), "new");
        Files.writeString(current.resolve("content.json"), "same");
        Files.writeString(candidate.resolve("content.json"), "same");

        assertTrue(ResourcePackFileOps.contentEqualsIgnoringManifest(current, candidate));
        Files.writeString(candidate.resolve("content.json"), "changed");
        assertFalse(ResourcePackFileOps.contentEqualsIgnoringManifest(current, candidate));
    }

    @Test
    void publishesDirectoryAndZipTogether() throws Exception {
        Path targetDirectory = temp.resolve("generated_pack");
        Path targetZip = temp.resolve("generated_pack.zip");
        Path candidateDirectory = temp.resolve("candidate_pack");
        Path candidateZip = temp.resolve("candidate.zip");
        Files.createDirectories(targetDirectory);
        Files.createDirectories(candidateDirectory);
        Files.writeString(targetDirectory.resolve("value.txt"), "old");
        Files.writeString(targetZip, "old zip");
        Files.writeString(candidateDirectory.resolve("value.txt"), "new");
        Files.writeString(candidateZip, "new zip");

        AtomicPackPublisher.publish(candidateDirectory, candidateZip, targetDirectory, targetZip);

        assertTrue(Files.readString(targetDirectory.resolve("value.txt")).equals("new"));
        assertTrue(Files.readString(targetZip).equals("new zip"));
        assertFalse(Files.exists(candidateDirectory));
        assertFalse(Files.exists(candidateZip));
    }
}
