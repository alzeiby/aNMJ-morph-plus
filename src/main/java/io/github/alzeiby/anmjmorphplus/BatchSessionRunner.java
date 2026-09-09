package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.io.DirectoryChooser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

final class BatchSessionRunner implements Runnable {

    private enum OutputState {
        COMMITTED,
        UNCHANGED,
        AMBIGUOUS
    }

    private final Supplier<Path> rootSelector;
    private final BatchCheckpointStore checkpointStore;
    private final BiConsumer<Path, BatchChoiceResolver> fileProcessor;
    private final BatchChoiceResolver.Prompter choicePrompter;
    private final Consumer<String> statusReporter;

    BatchSessionRunner() {
        this(
            BatchSessionRunner::chooseRoot,
            new BatchCheckpointStore(),
            new JavaBatchFileProcessor(),
            BatchChoiceResolver.interactivePrompter(),
            IJ::log
        );
    }

    BatchSessionRunner(
        final Supplier<Path> rootSelector,
        final BatchCheckpointStore checkpointStore,
        final BiConsumer<Path, BatchChoiceResolver> fileProcessor,
        final BatchChoiceResolver.Prompter choicePrompter,
        final Consumer<String> statusReporter
    ) {
        this.rootSelector = Objects.requireNonNull(rootSelector, "rootSelector");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
        this.fileProcessor = Objects.requireNonNull(fileProcessor, "fileProcessor");
        this.choicePrompter = Objects.requireNonNull(choicePrompter, "choicePrompter");
        this.statusReporter = Objects.requireNonNull(statusReporter, "statusReporter");
    }

    @Override
    public void run() {
        final Path root = rootSelector.get();
        if (root != null) {
            run(root);
        }
    }

    void run(final Path batchRoot) {
        final Path root = batchRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new BatchCheckpointStore.BatchCheckpointException("Batch root is not a directory: " + root);
        }

        final BatchCheckpointStore.Session session = checkpointStore.load(root);
        final BatchChoiceResolver choices = new BatchChoiceResolver(
            root,
            checkpointStore,
            session,
            choicePrompter
        );
        final List<Path> files = discover(root);
        final Set<String> collisions = outputStemCollisions(root, files);

        for (Path file : files) {
            if (!processOne(root, file, collisions, session, choices)) {
                break;
            }
        }
        reportSummary(session);
    }

    private boolean processOne(
        final Path root,
        final Path file,
        final Set<String> collisions,
        final BatchCheckpointStore.Session session,
        final BatchChoiceResolver choices
    ) {
        final String relative = BatchCheckpointStore.relativePath(root, file);
        final BasicFileAttributes attributes = attributes(file);
        final AnalysisOutputPaths outputs = AnalysisOutputPaths.forInput(file);
        BatchCheckpointStore.FileRecord record = session.files.get(relative);

        if (record != null && !record.matches(attributes.size(), attributes.lastModifiedTime().toMillis())) {
            if (record.status == BatchCheckpointStore.Status.SUCCEEDED ||
                record.status == BatchCheckpointStore.Status.NEEDS_REVIEW) {
                record.size = attributes.size();
                record.modifiedMillis = attributes.lastModifiedTime().toMillis();
                mark(record, BatchCheckpointStore.Status.NEEDS_REVIEW, "INPUT_CHANGED",
                    "Input changed after a prior committed or ambiguous run");
                checkpointStore.save(root, session);
                return true;
            }
            record = new BatchCheckpointStore.FileRecord(
                relative,
                attributes.size(),
                attributes.lastModifiedTime().toMillis()
            );
            session.files.put(relative, record);
        }

        if (record == null) {
            record = new BatchCheckpointStore.FileRecord(
                relative,
                attributes.size(),
                attributes.lastModifiedTime().toMillis()
            );
            session.files.put(relative, record);
        }

        if (record.status == BatchCheckpointStore.Status.SUCCEEDED) {
            final long minimumCsvLines = record.csvLinesBefore == 0 ? 3 : record.csvLinesBefore + 1;
            if (existingCleanedOutputCount(outputs) != 3 || countCsvLines(outputs.csv) < minimumCsvLines) {
                mark(record, BatchCheckpointStore.Status.NEEDS_REVIEW, "COMMITTED_OUTPUT_MISSING",
                    "A previously successful file no longer has its committed CSV/output artifacts");
                checkpointStore.save(root, session);
            }
            return true;
        }

        if (record.status == BatchCheckpointStore.Status.NEEDS_REVIEW) {
            return true;
        }

        if (record.status == BatchCheckpointStore.Status.RUNNING) {
            final OutputState recovered = outputState(outputs, record.csvLinesBefore);
            if (recovered == OutputState.COMMITTED) {
                mark(record, BatchCheckpointStore.Status.SUCCEEDED, "", "Recovered committed output after interruption");
                checkpointStore.save(root, session);
                return true;
            }
            if (recovered == OutputState.AMBIGUOUS) {
                mark(record, BatchCheckpointStore.Status.NEEDS_REVIEW, "OUTPUT_STATE_AMBIGUOUS",
                    "Interrupted run left an ambiguous CSV/cleaned-output state");
                checkpointStore.save(root, session);
                return true;
            }
            record.status = BatchCheckpointStore.Status.PENDING;
        }

        if (collisions.contains(collisionKey(root, file))) {
            mark(record, BatchCheckpointStore.Status.FAILED_PRECHECK, "OUTPUT_NAME_COLLISION",
                "Another input in this directory produces the same cleaned-image filenames");
            checkpointStore.save(root, session);
            return true;
        }

        if (existingCleanedOutputCount(outputs) > 0) {
            mark(record, BatchCheckpointStore.Status.NEEDS_REVIEW, "PREEXISTING_OUTPUTS",
                "Cleaned outputs already exist without a successful checkpoint record");
            checkpointStore.save(root, session);
            return true;
        }

        record.csvLinesBefore = countCsvLines(outputs.csv);
        mark(record, BatchCheckpointStore.Status.RUNNING, "", "");
        checkpointStore.save(root, session);

        boolean stopSession = false;
        try {
            fileProcessor.accept(file, choices);
            final OutputState output = outputState(outputs, record.csvLinesBefore);
            if (output == OutputState.COMMITTED) {
                mark(record, BatchCheckpointStore.Status.SUCCEEDED, "", "");
            } else if (output == OutputState.UNCHANGED) {
                mark(record, BatchCheckpointStore.Status.FAILED_RUNTIME, "OUTPUT_NOT_COMMITTED",
                    "Workflow returned without committing one CSV row and all cleaned outputs");
            } else {
                mark(record, BatchCheckpointStore.Status.NEEDS_REVIEW, "OUTPUT_STATE_AMBIGUOUS",
                    "Workflow returned with an ambiguous CSV/cleaned-output state");
            }
        } catch (BatchCheckpointStore.BatchCheckpointException e) {
            throw e;
        } catch (BatchFileException e) {
            if (e.kind() == BatchFileException.Kind.CANCELLED) {
                stopSession = true;
            }
            final OutputState output = outputState(outputs, record.csvLinesBefore);
            if (output != OutputState.UNCHANGED) {
                mark(record, BatchCheckpointStore.Status.NEEDS_REVIEW, "OUTPUT_STATE_AMBIGUOUS",
                    "Workflow failed after changing CSV or cleaned outputs: " + e.getMessage());
            } else if (e.kind() == BatchFileException.Kind.PRECHECK) {
                mark(record, BatchCheckpointStore.Status.FAILED_PRECHECK, e.reasonCode(), e.getMessage());
            } else if (e.kind() == BatchFileException.Kind.CANCELLED) {
                mark(record, BatchCheckpointStore.Status.CANCELLED, e.reasonCode(), e.getMessage());
            } else {
                mark(record, BatchCheckpointStore.Status.FAILED_RUNTIME, e.reasonCode(), e.getMessage());
            }
        } catch (RuntimeException e) {
            final OutputState output = outputState(outputs, record.csvLinesBefore);
            if (output != OutputState.UNCHANGED) {
                mark(record, BatchCheckpointStore.Status.NEEDS_REVIEW, "OUTPUT_STATE_AMBIGUOUS",
                    "Unexpected failure after output changed: " + Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
            } else {
                mark(record, BatchCheckpointStore.Status.FAILED_RUNTIME, "MACRO_ERROR",
                    Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
            }
        }
        checkpointStore.save(root, session);
        return !stopSession;
    }

    static List<Path> discover(final Path root) {
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> !isExcluded(root, path))
                .filter(path -> SupportedImageFormat.fromName(path.getFileName().toString()).isPresent())
                .forEach(files::add);
        } catch (IOException e) {
            throw new BatchCheckpointStore.BatchCheckpointException("Could not enumerate batch folder: " + root, e);
        }
        files.sort(
            Comparator.comparing((Path path) -> BatchCheckpointStore.relativePath(root, path).toLowerCase(Locale.ROOT))
                .thenComparing(path -> BatchCheckpointStore.relativePath(root, path))
        );
        return files;
    }

    private static boolean isExcluded(final Path root, final Path file) {
        final Path relative = root.relativize(file);
        final Path parent = relative.getParent();
        if (parent == null) {
            return false;
        }
        for (Path component : parent) {
            final String name = component.toString().toLowerCase(Locale.ROOT);
            if (name.contains("cleaned_images") || name.equals(BatchCheckpointStore.SESSION_DIRECTORY)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> outputStemCollisions(final Path root, final List<Path> files) {
        final Map<String, Integer> counts = new HashMap<>();
        for (Path file : files) {
            final String key = collisionKey(root, file);
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        final Set<String> collisions = new HashSet<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 1) {
                collisions.add(entry.getKey());
            }
        }
        return collisions;
    }

    private static String collisionKey(final Path root, final Path file) {
        final Path parent = file.getParent() == null ? root : file.getParent();
        return BatchCheckpointStore.relativePath(root, parent).toLowerCase(Locale.ROOT) + "|" +
            stem(file).toLowerCase(Locale.ROOT);
    }

    private static BasicFileAttributes attributes(final Path file) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException e) {
            throw new BatchCheckpointStore.BatchCheckpointException("Could not stat batch input: " + file, e);
        }
    }

    private static OutputState outputState(final AnalysisOutputPaths outputs, final long csvLinesBefore) {
        final long currentLines = countCsvLines(outputs.csv);
        final long expectedLines = csvLinesBefore == 0 ? 3 : csvLinesBefore + 1;
        final int outputCount = existingCleanedOutputCount(outputs);
        if (currentLines == expectedLines && outputCount == 3) {
            return OutputState.COMMITTED;
        }
        if (currentLines == csvLinesBefore && outputCount == 0) {
            return OutputState.UNCHANGED;
        }
        return OutputState.AMBIGUOUS;
    }

    static long countCsvLines(final Path csv) {
        if (!Files.exists(csv)) {
            return 0;
        }
        try {
            if (Files.size(csv) == 0) {
                return 0;
            }
            long lines = 0;
            int last = -1;
            try (InputStream stream = Files.newInputStream(csv)) {
                int value;
                while ((value = stream.read()) != -1) {
                    if (value == '\n') {
                        lines++;
                    }
                    last = value;
                }
            }
            return last == '\n' ? lines : lines + 1;
        } catch (IOException e) {
            throw new BatchCheckpointStore.BatchCheckpointException("Could not inspect CSV: " + csv, e);
        }
    }

    private static int existingCleanedOutputCount(final AnalysisOutputPaths outputs) {
        int count = 0;
        for (Path output : outputs.cleanedOutputs()) {
            try {
                if (Files.isRegularFile(output) && Files.size(output) > 0) {
                    count++;
                }
            } catch (IOException e) {
                throw new BatchCheckpointStore.BatchCheckpointException("Could not inspect cleaned output: " + output, e);
            }
        }
        return count;
    }

    private static String stem(final Path input) {
        final String name = input.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void mark(
        final BatchCheckpointStore.FileRecord record,
        final BatchCheckpointStore.Status status,
        final String reasonCode,
        final String reason
    ) {
        record.status = status;
        record.reasonCode = reasonCode == null ? "" : reasonCode;
        record.reason = reason == null ? "" : reason;
    }

    private void reportSummary(final BatchCheckpointStore.Session session) {
        final Map<BatchCheckpointStore.Status, Integer> counts = new HashMap<>();
        for (BatchCheckpointStore.FileRecord record : session.files.values()) {
            counts.put(record.status, counts.getOrDefault(record.status, 0) + 1);
        }
        statusReporter.accept(
            "aNMJ-morph+ batch: " + counts.getOrDefault(BatchCheckpointStore.Status.SUCCEEDED, 0) + " succeeded, " +
            counts.getOrDefault(BatchCheckpointStore.Status.FAILED_PRECHECK, 0) + " preflight failures, " +
            counts.getOrDefault(BatchCheckpointStore.Status.FAILED_RUNTIME, 0) + " runtime failures, " +
            counts.getOrDefault(BatchCheckpointStore.Status.CANCELLED, 0) + " cancelled, " +
            counts.getOrDefault(BatchCheckpointStore.Status.NEEDS_REVIEW, 0) + " need review"
        );
    }

    private static Path chooseRoot() {
        final DirectoryChooser chooser = new DirectoryChooser("Select directory with images to process");
        final String directory = chooser.getDirectory();
        return directory == null ? null : Path.of(directory);
    }

}
