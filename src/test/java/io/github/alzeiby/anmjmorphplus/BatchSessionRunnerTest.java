package io.github.alzeiby.anmjmorphplus;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BatchSessionRunnerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void discoveryIsDeterministicAndSkipsGeneratedDirectories() throws Exception {
        final Path root = temporaryFolder.newFolder("discover").toPath();
        touch(root.resolve("b.TIFF"));
        touch(root.resolve("A file.lsm"));
        touch(root.resolve("notes.txt"));
        touch(root.resolve("nested").resolve("c.png"));
        touch(root.resolve("source_cleaned_images.tif"));
        touch(root.resolve("nested").resolve("cleaned_images").resolve("ignored.tif"));
        touch(root.resolve("some_cleaned_images_backup").resolve("ignored2.tif"));
        touch(root.resolve(BatchCheckpointStore.SESSION_DIRECTORY).resolve("ignored.tif"));

        final List<Path> discovered = BatchSessionRunner.discover(root);
        final List<String> names = new ArrayList<>();
        for (Path path : discovered) {
            names.add(BatchCheckpointStore.relativePath(root, path));
        }

        assertEquals(List.of("A file.lsm", "b.TIFF", "nested/c.png", "source_cleaned_images.tif"), names);
    }

    @Test
    public void userCancellationStopsTheSessionBeforeLaterFiles() throws Exception {
        final Path root = temporaryFolder.newFolder("cancel").toPath();
        final Path first = touch(root.resolve("a.tif"));
        final Path second = touch(root.resolve("b.tif"));
        touch(root.resolve("c.tif"));
        final BatchCheckpointStore store = new BatchCheckpointStore();
        final List<String> processed = new ArrayList<>();

        runner(root, store, (path, choices) -> {
            processed.add(path.getFileName().toString());
            if (path.equals(first)) {
                commitOutputs(path);
            } else if (path.equals(second)) {
                throw BatchFileException.cancelled("synthetic cancel");
            } else {
                throw new AssertionError("files after cancellation must not run");
            }
        }).run();

        assertEquals(List.of("a.tif", "b.tif"), processed);
        final BatchCheckpointStore.Session session = store.load(root);
        assertEquals(BatchCheckpointStore.Status.SUCCEEDED, session.files.get("a.tif").status);
        assertEquals(BatchCheckpointStore.Status.CANCELLED, session.files.get("b.tif").status);
        assertFalse(session.files.containsKey("c.tif"));
    }

    @Test
    public void cancellationAfterOutputChangesStillStopsTheSession() throws Exception {
        final Path root = temporaryFolder.newFolder("cancel-after-output").toPath();
        final Path first = touch(root.resolve("a.tif"));
        touch(root.resolve("b.tif"));
        final BatchCheckpointStore store = new BatchCheckpointStore();
        final List<String> processed = new ArrayList<>();

        runner(root, store, (path, choices) -> {
            processed.add(path.getFileName().toString());
            if (path.equals(first)) {
                commitOutputs(path);
                throw BatchFileException.cancelled("cancel after commit");
            }
            throw new AssertionError("files after cancellation must not run");
        }).run();

        assertEquals(List.of("a.tif"), processed);
        final BatchCheckpointStore.Session session = store.load(root);
        assertEquals(BatchCheckpointStore.Status.NEEDS_REVIEW, session.files.get("a.tif").status);
        assertEquals("OUTPUT_STATE_AMBIGUOUS", session.files.get("a.tif").reasonCode);
        assertFalse(session.files.containsKey("b.tif"));
    }

    @Test
    public void runtimeFailureDoesNotStopLaterFiles() throws Exception {
        final Path root = temporaryFolder.newFolder("continue").toPath();
        final Path a = touch(root.resolve("a.tif"));
        final Path b = touch(root.resolve("b.tif"));
        final Path c = touch(root.resolve("c.tif"));
        final BatchCheckpointStore store = new BatchCheckpointStore();
        final List<String> processed = new ArrayList<>();
        final BatchSessionRunner runner = runner(root, store, (path, choices) -> {
            processed.add(path.getFileName().toString());
            if (path.equals(b)) {
                throw BatchFileException.runtime("MACRO_ERROR", "synthetic failure", null);
            }
            commitOutputs(path);
        });

        runner.run();

        assertEquals(List.of("a.tif", "b.tif", "c.tif"), processed);
        final BatchCheckpointStore.Session session = store.load(root);
        assertEquals(BatchCheckpointStore.Status.SUCCEEDED, session.files.get("a.tif").status);
        assertEquals(BatchCheckpointStore.Status.FAILED_RUNTIME, session.files.get("b.tif").status);
        assertEquals("MACRO_ERROR", session.files.get("b.tif").reasonCode);
        assertEquals(BatchCheckpointStore.Status.SUCCEEDED, session.files.get("c.tif").status);
        assertEquals(4, BatchSessionRunner.countCsvLines(root.resolve("raw_data_table.csv")));
    }

    @Test
    public void interruptedCommittedRunIsRecoveredWithoutDuplicateRow() throws Exception {
        final Path root = temporaryFolder.newFolder("recover").toPath();
        final Path input = touch(root.resolve("sample.tif"));
        final BatchCheckpointStore store = new BatchCheckpointStore();
        final BatchCheckpointStore.Session session = new BatchCheckpointStore.Session();
        final BatchCheckpointStore.FileRecord record = record(root, input);
        record.status = BatchCheckpointStore.Status.RUNNING;
        record.csvLinesBefore = 0;
        session.files.put("sample.tif", record);
        store.save(root, session);
        commitOutputs(input);
        final AtomicInteger calls = new AtomicInteger();

        runner(root, store, (path, choices) -> calls.incrementAndGet()).run();

        assertEquals(0, calls.get());
        assertEquals(BatchCheckpointStore.Status.SUCCEEDED, store.load(root).files.get("sample.tif").status);
        assertEquals(3, BatchSessionRunner.countCsvLines(root.resolve("raw_data_table.csv")));
    }

    @Test
    public void interruptedUnchangedRunRetries() throws Exception {
        final Path root = temporaryFolder.newFolder("retry").toPath();
        final Path input = touch(root.resolve("sample.tif"));
        final BatchCheckpointStore store = new BatchCheckpointStore();
        final BatchCheckpointStore.Session session = new BatchCheckpointStore.Session();
        final BatchCheckpointStore.FileRecord record = record(root, input);
        record.status = BatchCheckpointStore.Status.RUNNING;
        record.csvLinesBefore = 0;
        session.files.put("sample.tif", record);
        store.save(root, session);
        final AtomicInteger calls = new AtomicInteger();

        runner(root, store, (path, choices) -> {
            calls.incrementAndGet();
            commitOutputs(path);
        }).run();

        assertEquals(1, calls.get());
        assertEquals(BatchCheckpointStore.Status.SUCCEEDED, store.load(root).files.get("sample.tif").status);
    }

    @Test
    public void ambiguousInterruptedStateIsNotRerun() throws Exception {
        final Path root = temporaryFolder.newFolder("ambiguous").toPath();
        final Path input = touch(root.resolve("sample.tif"));
        final BatchCheckpointStore store = new BatchCheckpointStore();
        final BatchCheckpointStore.Session session = new BatchCheckpointStore.Session();
        final BatchCheckpointStore.FileRecord record = record(root, input);
        record.status = BatchCheckpointStore.Status.RUNNING;
        record.csvLinesBefore = 0;
        session.files.put("sample.tif", record);
        store.save(root, session);
        Files.writeString(root.resolve("raw_data_table.csv"), "header1\nheader2\n", StandardCharsets.UTF_8);
        final AtomicInteger calls = new AtomicInteger();

        runner(root, store, (path, choices) -> calls.incrementAndGet()).run();

        assertEquals(0, calls.get());
        final BatchCheckpointStore.FileRecord reloaded = store.load(root).files.get("sample.tif");
        assertEquals(BatchCheckpointStore.Status.NEEDS_REVIEW, reloaded.status);
        assertEquals("OUTPUT_STATE_AMBIGUOUS", reloaded.reasonCode);
    }

    @Test
    public void outputStemCollisionFailsBothWithoutProcessing() throws Exception {
        final Path root = temporaryFolder.newFolder("collision").toPath();
        touch(root.resolve("same.tif"));
        touch(root.resolve("same.lsm"));
        final BatchCheckpointStore store = new BatchCheckpointStore();
        final AtomicInteger calls = new AtomicInteger();

        runner(root, store, (path, choices) -> calls.incrementAndGet()).run();

        assertEquals(0, calls.get());
        final BatchCheckpointStore.Session session = store.load(root);
        assertEquals("OUTPUT_NAME_COLLISION", session.files.get("same.tif").reasonCode);
        assertEquals("OUTPUT_NAME_COLLISION", session.files.get("same.lsm").reasonCode);
    }

    @Test
    public void changedSuccessfulInputRequiresReviewInsteadOfAppendingAgain() throws Exception {
        final Path root = temporaryFolder.newFolder("changed").toPath();
        final Path input = touch(root.resolve("sample.tif"));
        final BatchCheckpointStore store = new BatchCheckpointStore();
        final BatchCheckpointStore.Session session = new BatchCheckpointStore.Session();
        final BatchCheckpointStore.FileRecord record = record(root, input);
        record.status = BatchCheckpointStore.Status.SUCCEEDED;
        session.files.put("sample.tif", record);
        store.save(root, session);
        Files.writeString(input, "changed", StandardCharsets.UTF_8);
        final AtomicInteger calls = new AtomicInteger();

        runner(root, store, (path, choices) -> calls.incrementAndGet()).run();

        assertEquals(0, calls.get());
        final BatchCheckpointStore.FileRecord reloaded = store.load(root).files.get("sample.tif");
        assertEquals(BatchCheckpointStore.Status.NEEDS_REVIEW, reloaded.status);
        assertEquals("INPUT_CHANGED", reloaded.reasonCode);
    }

    @Test
    public void missingArtifactsFromSuccessfulCheckpointRequireReview() throws Exception {
        final Path root = temporaryFolder.newFolder("missing-artifacts").toPath();
        final Path input = touch(root.resolve("sample.tif"));
        final BatchCheckpointStore store = new BatchCheckpointStore();
        final BatchCheckpointStore.Session session = new BatchCheckpointStore.Session();
        final BatchCheckpointStore.FileRecord record = record(root, input);
        record.status = BatchCheckpointStore.Status.SUCCEEDED;
        record.csvLinesBefore = 0;
        session.files.put("sample.tif", record);
        store.save(root, session);
        final AtomicInteger calls = new AtomicInteger();

        runner(root, store, (path, choices) -> calls.incrementAndGet()).run();

        assertEquals(0, calls.get());
        final BatchCheckpointStore.FileRecord reloaded = store.load(root).files.get("sample.tif");
        assertEquals(BatchCheckpointStore.Status.NEEDS_REVIEW, reloaded.status);
        assertEquals("COMMITTED_OUTPUT_MISSING", reloaded.reasonCode);
    }

    private static BatchSessionRunner runner(
        final Path root,
        final BatchCheckpointStore store,
        final BatchSessionRunner.FileProcessor processor
    ) {
        return new BatchSessionRunner(
            () -> root,
            store,
            processor,
            new BatchChoiceResolver.Prompter() {
                @Override
                public BatchChoiceResolver.PromptResult<BatchChoiceResolver.TwoPlaneChoice> promptTwoPlane(final InputSignature signature) {
                    throw new AssertionError("fake processor should not prompt");
                }

                @Override
                public BatchChoiceResolver.PromptResult<BatchChoiceResolver.ChannelChoice> promptChannels(final InputSignature signature, final int channelCount) {
                    throw new AssertionError("fake processor should not prompt");
                }
            },
            ignored -> { }
        );
    }

    private static Path touch(final Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[] {1});
        return path;
    }

    private static BatchCheckpointStore.FileRecord record(final Path root, final Path input) throws Exception {
        return new BatchCheckpointStore.FileRecord(
            BatchCheckpointStore.relativePath(root, input),
            Files.size(input),
            Files.getLastModifiedTime(input).toMillis()
        );
    }

    private static void commitOutputs(final Path input) {
        try {
            final Path csv = input.getParent().resolve("raw_data_table.csv");
            final long before = BatchSessionRunner.countCsvLines(csv);
            if (before == 0) {
                Files.writeString(csv, "header1\nheader2\nrow\n", StandardCharsets.UTF_8);
            } else {
                Files.writeString(csv, "row\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            }
            final Path cleaned = input.getParent().resolve("cleaned_images");
            Files.createDirectories(cleaned);
            final String name = input.getFileName().toString();
            final int dot = name.lastIndexOf('.');
            final String stem = dot > 0 ? name.substring(0, dot) : name;
            for (String prefix : new String[] {"axon_terminal", "muscle_endplate", "muscle_intermediate_endplate"}) {
                Files.write(cleaned.resolve(prefix + stem + ".tif"), new byte[] {1});
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
