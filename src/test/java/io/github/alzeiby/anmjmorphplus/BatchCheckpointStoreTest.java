package io.github.alzeiby.anmjmorphplus;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BatchCheckpointStoreTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void roundTripsChoicesReasonsAndFileState() throws Exception {
        final Path root = temporaryFolder.newFolder("batch").toPath();
        final BatchCheckpointStore store = new BatchCheckpointStore();
        final BatchCheckpointStore.Session session = new BatchCheckpointStore.Session();
        session.choices.put("channels|sig\tα", "1,2\nremembered");
        final BatchCheckpointStore.FileRecord record = new BatchCheckpointStore.FileRecord("nested/input file.TIF", 12, 34);
        record.status = BatchCheckpointStore.Status.FAILED_RUNTIME;
        record.csvLinesBefore = 7;
        record.reasonCode = "MACRO_ERROR";
        record.reason = "bad\tthing\nnext";
        session.files.put(record.relativePath, record);

        store.save(root, session);
        final BatchCheckpointStore.Session loaded = store.load(root);

        assertEquals("1,2\nremembered", loaded.choices.get("channels|sig\tα"));
        final BatchCheckpointStore.FileRecord loadedRecord = loaded.files.get("nested/input file.TIF");
        assertEquals(BatchCheckpointStore.Status.FAILED_RUNTIME, loadedRecord.status);
        assertEquals(7, loadedRecord.csvLinesBefore);
        assertEquals("bad\tthing\nnext", loadedRecord.reason);
        assertTrue(store.checkpointPath(root).toFile().isFile());
        assertFalse(root.resolve(BatchCheckpointStore.SESSION_DIRECTORY).resolve(BatchCheckpointStore.SESSION_FILE + ".tmp").toFile().exists());
    }

    @Test
    public void missingCheckpointStartsEmpty() throws Exception {
        final Path root = temporaryFolder.newFolder("empty").toPath();
        final BatchCheckpointStore.Session loaded = new BatchCheckpointStore().load(root);
        assertTrue(loaded.files.isEmpty());
        assertTrue(loaded.choices.isEmpty());
    }
}
