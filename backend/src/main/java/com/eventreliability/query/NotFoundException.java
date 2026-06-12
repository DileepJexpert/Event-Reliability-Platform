package com.eventreliability.query;

/** Thrown when a requested resource (failure, incident) is not present in the read model. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
