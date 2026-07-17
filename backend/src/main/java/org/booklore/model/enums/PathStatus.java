package org.booklore.model.enums;

public enum PathStatus {
    OK,
    MISSING,
    UNREADABLE,
    // The probe could not determine this path's status within its time budget (e.g. a hung
    // network mount). Deliberately distinct from MISSING: reporting a hung mount as "missing"
    // would tell the admin the directory is gone when it may be perfectly fine.
    UNKNOWN
}
