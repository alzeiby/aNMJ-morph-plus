package io.github.alzeiby.anmjmorphplus;

final class ImageLoadingException extends RuntimeException {

    ImageLoadingException(final String message) {
        super(message);
    }

    ImageLoadingException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
