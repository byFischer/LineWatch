package com.emre.linewatch.collector;

public interface ModemCollector {

    /** Modemden guncel DSL olcumunu alir. */
    LineSample collect() throws CollectorException;

    /** Modemdeki oturumu serbest birakir (uygulama kapanirken). */
    default void close() {}

    /** Modemi yeniden baslatir. Desteklenmiyorsa exception firlatir. */
    default void reboot() throws CollectorException {
        throw new CollectorException(CollectorException.Kind.PROTOCOL, "Reboot desteklenmiyor", null);
    }

    class CollectorException extends Exception {
        public enum Kind { UNREACHABLE, AUTH_FAILED, PROTOCOL }

        private final Kind kind;

        public CollectorException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind kind() { return kind; }
    }
}
