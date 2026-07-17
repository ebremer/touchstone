package com.ebremer.touchstone.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TouchstoneTest {

    @Test
    void nameIsTheSingleSourceOfTruth() {
        assertThat(Touchstone.NAME).isEqualTo("Touchstone");
    }

    @Test
    void versionIsFilteredFromTheBuild() {
        assertThat(Touchstone.version())
                .isNotEqualTo("unknown")
                .matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?");
    }
}
