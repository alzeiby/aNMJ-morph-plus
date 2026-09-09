package io.github.alzeiby.anmjmorphplus;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

final class BatchCheckpointStore {

    static final String SESSION_DIRECTORY = ".anmj-morph-plus";
    static final String SESSION_FILE = "session-v1.tsv";

    enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED_PRECHECK,
        FAILED_RUNTIME,
        CANCELLED,
        NEEDS_REVIEW
    }

    static final class FileRecord {
        final String relativePath;
        long size;
        long modifiedMillis;
        Status status;
        long csvLinesBefore;
        String reasonCode;
        String reason;

        FileRecord(final String relativePath, final long size, final long modifiedMillis) {
            this.relativePath = relativePath;
            this.size = size;
            this.modifiedMillis = modifiedMillis;
            this.status = Status.PENDING;
            this.csvLinesBefore = -1;
            this.reasonCode = "";
            this.reason = "";
        }

        boolean matches(final long currentSize, final long currentModifiedMillis) {
            return size == currentSize && modifiedMillis == currentModifiedMillis;
        }
    }

    static final class Session {
        final Map<String, FileRecord> files = new LinkedHashMap<>();
        final Map<String, String> choices = new LinkedHashMap<>();
    }

    Session load(final Path root) {
        final Path checkpoint = checkpointPath(root);
        final Session session = new Session();
        if (!Files.exists(checkpoint)) {
            return session;
        }
        try {
            boolean sawMetadata = false;
            for (String line : Files.readAllLines(checkpoint, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                final String[] fields = line.split("\\t", -1);
                if (fields.length >= 3 && "META".equals(fields[0])) {
                    if (!"version".equals(fields[1]) || !"1".equals(fields[2])) {
                        throw new BatchCheckpointException("Unsupported batch checkpoint version");
                    }
                    sawMetadata = true;
                } else if (fields.length == 3 && "CHOICE".equals(fields[0])) {
                    session.choices.put(unescape(fields[1]), unescape(fields[2]));
                } else if (fields.length == 8 && "FILE".equals(fields[0])) {
                    final FileRecord record = new FileRecord(
                        unescape(fields[1]),
                        Long.parseLong(fields[2]),
                        Long.parseLong(fields[3])
                    );
                    record.status = Status.valueOf(fields[4]);
                    record.csvLinesBefore = Long.parseLong(fields[5]);
                    record.reasonCode = unescape(fields[6]);
                    record.reason = unescape(fields[7]);
                    session.files.put(record.relativePath, record);
                } else {
                    throw new BatchCheckpointException("Malformed batch checkpoint line: " + line);
                }
            }
            if (!sawMetadata) {
                throw new BatchCheckpointException("Batch checkpoint is missing version metadata");
            }
            return session;
        } catch (IOException | IllegalArgumentException e) {
            throw new BatchCheckpointException("Could not read batch checkpoint: " + checkpoint, e);
        }
    }

    void save(final Path root, final Session session) {
        final Path directory = root.resolve(SESSION_DIRECTORY);
        final Path checkpoint = directory.resolve(SESSION_FILE);
        final Path temporary = directory.resolve(SESSION_FILE + ".tmp");
        try {
            Files.createDirectories(directory);
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write("META\tversion\t1");
                writer.newLine();
                for (Map.Entry<String, String> choice : session.choices.entrySet()) {
                    writer.write("CHOICE\t" + escape(choice.getKey()) + "\t" + escape(choice.getValue()));
                    writer.newLine();
                }
                for (FileRecord record : session.files.values()) {
                    writer.write(
                        "FILE\t" + escape(record.relativePath) +
                        "\t" + record.size +
                        "\t" + record.modifiedMillis +
                        "\t" + record.status.name() +
                        "\t" + record.csvLinesBefore +
                        "\t" + escape(record.reasonCode) +
                        "\t" + escape(record.reason)
                    );
                    writer.newLine();
                }
            }
            try {
                Files.move(
                    temporary,
                    checkpoint,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, checkpoint, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BatchCheckpointException("Could not write batch checkpoint: " + checkpoint, e);
        }
    }

    Path checkpointPath(final Path root) {
        return root.resolve(SESSION_DIRECTORY).resolve(SESSION_FILE);
    }

    static String relativePath(final Path root, final Path file) {
        return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
            .toString().replace('\\', '/');
    }

    private static String escape(final String value) {
        return value.replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
    }

    private static String unescape(final String value) {
        final StringBuilder decoded = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            final char current = value.charAt(i);
            if (!escaped && current == '\\') {
                escaped = true;
                continue;
            }
            if (escaped) {
                if (current == 't') decoded.append('\t');
                else if (current == 'r') decoded.append('\r');
                else if (current == 'n') decoded.append('\n');
                else decoded.append(current);
                escaped = false;
            } else {
                decoded.append(current);
            }
        }
        if (escaped) {
            decoded.append('\\');
        }
        return decoded.toString();
    }

    static final class BatchCheckpointException extends RuntimeException {
        BatchCheckpointException(final String message) {
            super(message);
        }

        BatchCheckpointException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
