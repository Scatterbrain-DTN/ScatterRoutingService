package com.example.uscatterbrain.db;

public interface DatastoreEntity {
    enum entityType {
        TYPE_MESSAGE, TYPE_IDENTITY, TYPE_FILE
    }

    entityType getType();
}
