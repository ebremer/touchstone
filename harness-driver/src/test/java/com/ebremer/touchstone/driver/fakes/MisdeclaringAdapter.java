package com.ebremer.touchstone.driver.fakes;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import com.ebremer.touchstone.fixtures.client.RefAdapter;
import com.ebremer.touchstone.fixtures.client.RefLwsClient.Flaw;

/**
 * The reference adapter with a hello that leaves {@code read} out of its operations, although it
 * implements it: an adapter the checker must fail, since an operation it does not list must answer
 * unsupported.
 */
public final class MisdeclaringAdapter {

    private MisdeclaringAdapter() {
    }

    public static void main(String[] args) throws IOException {
        PipedInputStream fromReference = new PipedInputStream(1 << 16);
        PipedOutputStream referenceOut = new PipedOutputStream(fromReference);
        Thread reference = Thread.ofPlatform().start(() -> {
            try (referenceOut) {
                RefAdapter.serve(System.in, referenceOut, Flaw.NONE);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        byte[] buffer = new byte[1 << 16];
        StringBuilder line = new StringBuilder();
        boolean first = true;
        int n;
        while ((n = fromReference.read(buffer)) >= 0) {
            line.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
            int end;
            while ((end = line.indexOf("\n")) >= 0) {
                String out = line.substring(0, end);
                line.delete(0, end + 1);
                if (first) {
                    out = out.replace("\"read\",", "");
                    first = false;
                }
                System.out.print(out + "\n");
                System.out.flush();
            }
        }
        try {
            reference.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.exit(0);
    }
}
