package com.ebremer.touchstone.driver.protocol;

/**
 * The adapter broke the protocol, exited or stopped answering (driver/PROTOCOL.md section 2). It
 * ends the client's drive; it is never a finding about the client.
 */
public class BrokenAdapterException extends RuntimeException {

    public BrokenAdapterException(String message) {
        super(message);
    }
}
