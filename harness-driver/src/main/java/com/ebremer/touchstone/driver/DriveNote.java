package com.ebremer.touchstone.driver;

/**
 * What became of one rule's drive (OBSERVATION.md section 12). A note says where a drive stopped and
 * why; it never decides the rule's outcome, which the session judges from the traffic.
 *
 * @param rule   the rule's name
 * @param status how the drive went
 * @param step   the step it stopped at, from 1, or 0
 * @param reason why it stopped or never started, or null
 */
public record DriveNote(String rule, Status status, int step, String reason) {

    public enum Status {
        /** Every step ran. */
        COMPLETED,
        /** A step ended in an error it does not allow, or a capture selected nothing. */
        STOPPED,
        /** The client lacks an operation, or the storage a service, the drive needs. */
        NOT_STARTED,
        /** The rule has no drive: other rules' traffic tries it. */
        NO_DRIVE
    }

    static DriveNote completed(String rule) {
        return new DriveNote(rule, Status.COMPLETED, 0, null);
    }

    static DriveNote noDrive(String rule) {
        return new DriveNote(rule, Status.NO_DRIVE, 0, null);
    }

    static DriveNote notStarted(String rule, String reason) {
        return new DriveNote(rule, Status.NOT_STARTED, 0, reason);
    }

    static DriveNote stopped(String rule, int step, String reason) {
        return new DriveNote(rule, Status.STOPPED, step, reason);
    }
}
