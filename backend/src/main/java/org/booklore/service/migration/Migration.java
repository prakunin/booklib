package org.booklore.service.migration;

public interface Migration {
    String getKey();

    String getDescription();

    void execute();

    default boolean isTransactional() {
        return true;
    }
}
