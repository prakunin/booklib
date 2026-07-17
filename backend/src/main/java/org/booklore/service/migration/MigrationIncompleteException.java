package org.booklore.service.migration;

public class MigrationIncompleteException extends RuntimeException {

    public MigrationIncompleteException(String message) {
        super(message);
    }
}
