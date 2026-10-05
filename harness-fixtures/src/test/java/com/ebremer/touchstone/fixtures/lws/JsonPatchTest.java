package com.ebremer.touchstone.fixtures.lws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The reference server's JSON Patch (RFC 6902), on examples from the RFC's Appendix A. */
class JsonPatchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theOperationsApplyAsAppendixADescribes() throws Exception {
        assertThat(apply("{\"foo\": \"bar\"}", "[{\"op\": \"add\", \"path\": \"/baz\", \"value\": \"qux\"}]"))
                .isEqualTo(json("{\"baz\": \"qux\", \"foo\": \"bar\"}"));
        assertThat(apply("{\"foo\": [\"bar\", \"baz\"]}", "[{\"op\": \"add\", \"path\": \"/foo/1\", \"value\": \"qux\"}]"))
                .isEqualTo(json("{\"foo\": [\"bar\", \"qux\", \"baz\"]}"));
        assertThat(apply("{\"foo\": [\"bar\"]}", "[{\"op\": \"add\", \"path\": \"/foo/-\", \"value\": [\"abc\", \"def\"]}]"))
                .isEqualTo(json("{\"foo\": [\"bar\", [\"abc\", \"def\"]]}"));
        assertThat(apply("{\"baz\": \"qux\", \"foo\": \"bar\"}", "[{\"op\": \"remove\", \"path\": \"/baz\"}]"))
                .isEqualTo(json("{\"foo\": \"bar\"}"));
        assertThat(apply("{\"foo\": [\"bar\", \"qux\", \"baz\"]}", "[{\"op\": \"remove\", \"path\": \"/foo/1\"}]"))
                .isEqualTo(json("{\"foo\": [\"bar\", \"baz\"]}"));
        assertThat(apply("{\"baz\": \"qux\", \"foo\": \"bar\"}", "[{\"op\": \"replace\", \"path\": \"/baz\", \"value\": \"boo\"}]"))
                .isEqualTo(json("{\"baz\": \"boo\", \"foo\": \"bar\"}"));
        assertThat(apply("{\"foo\": {\"bar\": \"baz\", \"waldo\": \"fred\"}, \"qux\": {\"corge\": \"grault\"}}",
                "[{\"op\": \"move\", \"from\": \"/foo/waldo\", \"path\": \"/qux/thud\"}]"))
                .isEqualTo(json("{\"foo\": {\"bar\": \"baz\"}, \"qux\": {\"corge\": \"grault\", \"thud\": \"fred\"}}"));
        assertThat(apply("{\"foo\": [\"all\", \"grass\", \"cows\", \"eat\"]}", "[{\"op\": \"move\", \"from\": \"/foo/1\", \"path\": \"/foo/3\"}]"))
                .isEqualTo(json("{\"foo\": [\"all\", \"cows\", \"eat\", \"grass\"]}"));
        assertThat(apply("{\"a\": {\"b\": 1}}", "[{\"op\": \"copy\", \"from\": \"/a\", \"path\": \"/c\"}]"))
                .isEqualTo(json("{\"a\": {\"b\": 1}, \"c\": {\"b\": 1}}"));
        assertThat(apply("{\"/\": 9, \"~1\": 10}", "[{\"op\": \"test\", \"path\": \"/~01\", \"value\": 10},"
                + " {\"op\": \"replace\", \"path\": \"/~1\", \"value\": 1.0}]"))
                .isEqualTo(json("{\"/\": 1.0, \"~1\": 10}"));
        // Numbers are equal by value, and object members in any order.
        assertThat(apply("{\"n\": 1, \"o\": {\"x\": 1, \"y\": 2}}", "[{\"op\": \"test\", \"path\": \"/n\", \"value\": 1.0},"
                + " {\"op\": \"test\", \"path\": \"/o\", \"value\": {\"y\": 2, \"x\": 1}}]"))
                .isEqualTo(json("{\"n\": 1, \"o\": {\"x\": 1, \"y\": 2}}"));
        assertThat(apply("{\"a\": 1}", "[{\"op\": \"replace\", \"path\": \"\", \"value\": [1]}]")).isEqualTo(json("[1]"));
    }

    @Test
    void aPatchThatFailsChangesNothing() throws Exception {
        JsonNode target = json("{\"baz\": \"qux\", \"foo\": [\"a\", 2, \"c\"]}");
        assertThatThrownBy(() -> JsonPatch.apply(target, json("[{\"op\": \"replace\", \"path\": \"/baz\", \"value\": \"boo\"},"
                + " {\"op\": \"test\", \"path\": \"/foo/1\", \"value\": \"2\"}]")))
                .isInstanceOfSatisfying(JsonPatch.Failure.class, f -> assertThat(f.status()).isEqualTo(409));
        assertThat(target).isEqualTo(json("{\"baz\": \"qux\", \"foo\": [\"a\", 2, \"c\"]}"));
    }

    @Test
    void aMalformedPatchIs400AndOneThatDoesNotFitIs409() {
        assertStatus("{}", "{\"op\": \"add\", \"path\": \"/a\", \"value\": 1}", 400);
        assertStatus("{}", "[{\"op\": \"add\", \"path\": \"/a\"}]", 400);
        assertStatus("{}", "[{\"op\": \"jump\", \"path\": \"/a\"}]", 400);
        assertStatus("{}", "[{\"op\": \"add\", \"path\": \"a\", \"value\": 1}]", 400);
        assertStatus("{}", "[{\"op\": \"add\", \"path\": \"/a~2\", \"value\": 1}]", 400);
        assertStatus("{}", "[{\"op\": \"move\", \"path\": \"/a\"}]", 400);
        assertStatus("{\"foo\": \"bar\"}", "[{\"op\": \"add\", \"path\": \"/baz/bat\", \"value\": \"qux\"}]", 409);
        assertStatus("{\"foo\": [\"bar\"]}", "[{\"op\": \"add\", \"path\": \"/foo/2\", \"value\": \"qux\"}]", 409);
        assertStatus("{\"foo\": [\"bar\"]}", "[{\"op\": \"add\", \"path\": \"/foo/01\", \"value\": \"qux\"}]", 409);
        assertStatus("{\"foo\": \"bar\"}", "[{\"op\": \"remove\", \"path\": \"/baz\"}]", 409);
        assertStatus("{\"foo\": \"bar\"}", "[{\"op\": \"replace\", \"path\": \"/baz\", \"value\": 1}]", 409);
        assertStatus("{\"a\": {\"b\": {}}}", "[{\"op\": \"move\", \"from\": \"/a\", \"path\": \"/a/b/c\"}]", 409);
        assertStatus("{\"foo\": \"bar\"}", "[{\"op\": \"test\", \"path\": \"/foo\", \"value\": \"baz\"}]", 409);
    }

    private static JsonNode apply(String target, String patch) throws Exception {
        return JsonPatch.apply(json(target), json(patch));
    }

    private static void assertStatus(String target, String patch, int status) {
        assertThatThrownBy(() -> apply(target, patch)).as(patch)
                .isInstanceOfSatisfying(JsonPatch.Failure.class, f -> assertThat(f.status()).isEqualTo(status));
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
