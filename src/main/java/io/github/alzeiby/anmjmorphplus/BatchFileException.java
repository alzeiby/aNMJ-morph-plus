package io.github.alzeiby.anmjmorphplus;

final class BatchFileException extends RuntimeException {

    final BatchCheckpointStore.Status status;
    final String reasonCode;

    private BatchFileException(
        final BatchCheckpointStore.Status status,
        final String reasonCode,
        final String message,
        final Throwable cause
    ) {
        super(message, cause);
        this.status = status;
        this.reasonCode = reasonCode;
    }

    static BatchFileException precheck(final String reasonCode, final String message) {
        return new BatchFileException(BatchCheckpointStore.Status.FAILED_PRECHECK, reasonCode, message, null);
    }

    static BatchFileException runtime(final String reasonCode, final String message, final Throwable cause) {
        return new BatchFileException(BatchCheckpointStore.Status.FAILED_RUNTIME, reasonCode, message, cause);
    }

    static BatchFileException cancelled(final String message) {
        return new BatchFileException(BatchCheckpointStore.Status.CANCELLED, "USER_CANCELLED", message, null);
    }
}
