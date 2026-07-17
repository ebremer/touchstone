package com.ebremer.touchstone.core.exec;

/** Run provisioning or credential resolution failed. */
public class ProvisioningException extends RuntimeException {

    public ProvisioningException(String message) {
        super(message);
    }

    public ProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
