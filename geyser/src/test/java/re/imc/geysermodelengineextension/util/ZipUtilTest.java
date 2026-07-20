package re.imc.geysermodelengineextension.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ZipUtilTest {

    @TempDir
    Path temp;

    @Test
    void outputIsStableAcrossCreationOrderAndSourceTimestamps() throws Exception {
        Path first = temp.resolve("first");
        Path second = temp.resolve("second");
        Files.createDirectories(first.resolve("z"));
        Files.createDirectories(second.resolve("z"));

        Files.writeString(first.resolve("z/b.txt"), "b", StandardCharsets.UTF_8);
        Files.writeString(first.resolve("a.txt"), "a", StandardCharsets.UTF_8);
        Files.writeString(second.resolve("a.txt"), "a", StandardCharsets.UTF_8);
        Files.writeString(second.resolve("z/b.txt"), "b", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(first.resolve("a.txt"), FileTime.from(Instant.ofEpochSecond(1000)));
        Files.setLastModifiedTime(second.resolve("a.txt"), FileTime.from(Instant.ofEpochSecond(900000)));

        assertArrayEquals(zip(first, null), zip(second, null));
    }

    @Test
    void entriesAreGloballySortedAndHaveFixedTime() throws Exception {
        Files.createDirectories(temp.resolve("a"));
        Files.writeString(temp.resolve("z.txt"), "z");
        Files.writeString(temp.resolve("a/c.txt"), "c");
        Files.writeString(temp.resolve("a.txt"), "a");

        List<String> names = new ArrayList<>();
        List<LocalDateTime> times = new ArrayList<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip(temp, "prefix")))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                names.add(entry.getName());
                times.add(entry.getTimeLocal());
            }
        }

        assertEquals(List.of("prefix/a.txt", "prefix/a/c.txt", "prefix/z.txt"), names);
        assertEquals(List.of(
                LocalDateTime.of(1980, 1, 1, 0, 0),
                LocalDateTime.of(1980, 1, 1, 0, 0),
                LocalDateTime.of(1980, 1, 1, 0, 0)), times);
    }

    private static byte[] zip(Path root, String prefix) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            ZipUtil.compressFolder(root.toFile(), prefix, output);
        }
        return bytes.toByteArray();
    }
}
