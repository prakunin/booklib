package org.booklore.service.migration;

public interface Migration {
    String getKey();

    String getDescription();

    default boolean runsInSingleTransaction() {
        return true;
    }

    void execute();
}
