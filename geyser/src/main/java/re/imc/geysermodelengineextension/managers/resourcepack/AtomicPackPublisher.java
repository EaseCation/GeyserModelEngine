package re.imc.geysermodelengineextension.managers.resourcepack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Publishes an already validated directory and ZIP, rolling back on failure. */
final class AtomicPackPublisher {

    private AtomicPackPublisher() {
    }

    static void publish(Path candidateDirectory, Path candidateZip, Path targetDirectory, Path targetZip)
            throws IOException {
        publish(candidateDirectory, candidateZip, null, targetDirectory, targetZip, null);
    }

    static void publish(
            Path candidateDirectory,
            Path candidateZip,
            Path candidateReport,
            Path targetDirectory,
            Path targetZip,
            Path targetReport
    ) throws IOException {
        String token = UUID.randomUUID().toString();
        Path directoryBackup = targetDirectory.resolveSibling(targetDirectory.getFileName() + ".backup-" + token);
        Path zipBackup = targetZip.resolveSibling(targetZip.getFileName() + ".backup-" + token);
        Path reportBackup = targetReport == null ? null
                : targetReport.resolveSibling(targetReport.getFileName() + ".backup-" + token);

        boolean directoryBackedUp = false;
        boolean directoryPublished = false;
        boolean zipBackedUp = false;
        boolean zipPublished = false;
        boolean reportBackedUp = false;
        boolean reportPublished = false;
        boolean success = false;

        try {
            if (Files.exists(targetDirectory)) {
                ResourcePackFileOps.moveAtomically(targetDirectory, directoryBackup);
                directoryBackedUp = true;
            }
            ResourcePackFileOps.moveAtomically(candidateDirectory, targetDirectory);
            directoryPublished = true;

            if (Files.exists(targetZip)) {
                ResourcePackFileOps.moveAtomically(targetZip, zipBackup);
                zipBackedUp = true;
            }
            ResourcePackFileOps.moveAtomically(candidateZip, targetZip);
            zipPublished = true;

            if (candidateReport != null) {
                if (targetReport == null) {
                    throw new IOException("Compatibility report target is missing");
                }
                if (Files.exists(targetReport)) {
                    ResourcePackFileOps.moveAtomically(targetReport, reportBackup);
                    reportBackedUp = true;
                }
                ResourcePackFileOps.moveAtomically(candidateReport, targetReport);
                reportPublished = true;
            }
            success = true;
        } catch (IOException publishError) {
            IOException rollbackError = rollback(
                    targetDirectory, targetZip, targetReport,
                    directoryBackup, zipBackup, reportBackup,
                    directoryBackedUp, directoryPublished,
                    zipBackedUp, zipPublished,
                    reportBackedUp, reportPublished);
            if (rollbackError != null) {
                publishError.addSuppressed(rollbackError);
            }
            throw publishError;
        } finally {
            if (success) {
                deleteQuietly(directoryBackup);
                deleteQuietly(zipBackup);
                deleteQuietly(reportBackup);
            }
        }
    }

    private static IOException rollback(
            Path targetDirectory,
            Path targetZip,
            Path targetReport,
            Path directoryBackup,
            Path zipBackup,
            Path reportBackup,
            boolean directoryBackedUp,
            boolean directoryPublished,
            boolean zipBackedUp,
            boolean zipPublished,
            boolean reportBackedUp,
            boolean reportPublished
    ) {
        IOException failure = null;

        if (targetReport != null) {
            try {
                if (reportPublished) {
                    Files.deleteIfExists(targetReport);
                }
                if (reportBackedUp && Files.exists(reportBackup)) {
                    ResourcePackFileOps.moveAtomically(reportBackup, targetReport);
                }
            } catch (IOException error) {
                failure = error;
            }
        }

        try {
            if (zipPublished) {
                Files.deleteIfExists(targetZip);
            }
            if (zipBackedUp && Files.exists(zipBackup)) {
                ResourcePackFileOps.moveAtomically(zipBackup, targetZip);
            }
        } catch (IOException error) {
            if (failure == null) {
                failure = error;
            } else {
                failure.addSuppressed(error);
            }
        }

        try {
            if (directoryPublished) {
                ResourcePackFileOps.deleteRecursively(targetDirectory);
            }
            if (directoryBackedUp && Files.exists(directoryBackup)) {
                ResourcePackFileOps.moveAtomically(directoryBackup, targetDirectory);
            }
        } catch (IOException error) {
            if (failure == null) {
                failure = error;
            } else {
                failure.addSuppressed(error);
            }
        }

        return failure;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            ResourcePackFileOps.deleteRecursively(path);
        } catch (IOException ignored) {
            // A stale backup is harmless; the newly published pack is complete.
        }
    }
}
