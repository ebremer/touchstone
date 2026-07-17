package com.ebremer.touchstone.core.exec;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateEngineTest {

    @Test
    void resolvesMultipleVariables() {
        String resolved = TemplateEngine.resolve(
                "${test.container}note?from=${run.root}",
                Map.of("test.container", "http://sut/c1/", "run.root", "http://sut/"));
        assertThat(resolved).isEqualTo("http://sut/c1/note?from=http://sut/");
    }

    @Test
    void leavesPlainStringsAlone() {
        assertThat(TemplateEngine.resolve("no variables here", Map.of())).isEqualTo("no variables here");
    }

    @Test
    void unresolvedVariablesAreErrors() {
        assertThatThrownBy(() -> TemplateEngine.resolve("${missing}", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("${missing}");
    }
}
