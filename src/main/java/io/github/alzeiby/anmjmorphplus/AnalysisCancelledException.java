package io.github.alzeiby.anmjmorphplus;

final class AnalysisCancelledException extends RuntimeException {
    AnalysisCancelledException() {
        super("Analysis cancelled by user");
    }
}
