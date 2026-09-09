package io.github.alzeiby.anmjmorphplus;

final class BatchFileException extends RuntimeException {

    enum Kind {
        PRECHECK,
        RUNTIME,
        CANCELLED
    }

    private final Kind kind;
    private final String reasonCode;

    private BatchFileException(final Kind kind, final String reasonCode, final String message, final Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.reasonCode = reasonCode;
    }

    static BatchFileException precheck(final String reasonCode, final String message) {
        return new BatchFileException(Kind.PRECHECK, reasonCode, message, null);
    }

    static BatchFileException runtime(final String reasonCode, final String message, final Throwable cause) {
        return new BatchFileException(Kind.RUNTIME, reasonCode, message, cause);
    }

    static BatchFileException cancelled(final String message) {
        return new BatchFileException(Kind.CANCELLED, "USER_CANCELLED", message, null);
    }

    Kind kind() {
        return kind;
    }

    String reasonCode() {
        return reasonCode;
    }
}
