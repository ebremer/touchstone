package com.ebremer.touchstone.core.engine;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A value computed on first use and then kept, including a failure: a derived variable that
 * could not be resolved once is not asked for again (EXECUTION.md section 3, "computed on
 * first use and cached"). Thread-safe, since tests run in parallel; a lock rather than
 * {@code synchronized}, because the computation sends HTTP requests and tests run on virtual
 * threads.
 */
final class Lazy<T> {

    private final ReentrantLock lock = new ReentrantLock();
    private final Supplier<T> supplier;
    private boolean done;
    private T value;
    private RuntimeException failure;

    Lazy(Supplier<T> supplier) {
        this.supplier = supplier;
    }

    T get() {
        lock.lock();
        try {
            if (!done) {
                try {
                    value = supplier.get();
                } catch (RuntimeException e) {
                    failure = e;
                }
                done = true;
            }
        } finally {
            lock.unlock();
        }
        if (failure != null) {
            throw failure;
        }
        return value;
    }
}
