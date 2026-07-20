package re.imc.geysermodelengineextension.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipUtil {

    private static final LocalDateTime ZIP_EPOCH = LocalDateTime.of(1980, 1, 1, 0, 0);

    public static void compressFolder(File folder, String folderName, ZipOutputStream zipOutputStream) throws IOException {
        Path root = folder.toPath();
        if (!Files.isDirectory(root)) {
            throw new IOException("ZIP source is not a directory: " + root);
        }

        List<Path> files;
        try (Stream<Path> paths = Files.walk(root)) {
            files = paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> toEntryName(root.relativize(path))))
                    .toList();
        }

        String prefix = folderName == null || folderName.isBlank()
                ? ""
                : folderName.replace('\\', '/').replaceAll("/+$", "") + "/";
        for (Path file : files) {
            addToZipFile(prefix + toEntryName(root.relativize(file)), file, zipOutputStream);
        }
    }

    private static String toEntryName(Path relativePath) {
        return relativePath.toString().replace(File.separatorChar, '/');
    }

    private static void addToZipFile(String fileName, Path file, ZipOutputStream zipOutputStream) throws IOException {
        ZipEntry entry = new ZipEntry(fileName);
        entry.setTimeLocal(ZIP_EPOCH);
        zipOutputStream.putNextEntry(entry);

        try (InputStream fileInputStream = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                zipOutputStream.write(buffer, 0, bytesRead);
            }
        }

        zipOutputStream.closeEntry();
    }
}
