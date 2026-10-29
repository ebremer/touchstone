package com.ebremer.touchstone.driver;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ebremer.touchstone.core.definitions.DriveStep;
import com.ebremer.touchstone.core.definitions.RuleDefinition;
import com.ebremer.touchstone.core.engine.Templates;
import com.ebremer.touchstone.driver.protocol.AdapterConnection;
import com.ebremer.touchstone.driver.protocol.Hello;
import com.ebremer.touchstone.driver.protocol.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Runs one rule's drive (OBSERVATION.md section 12): expands each operation's arguments, sends it
 * to the adapter, binds its captures, starts the rule's task where the drive says, and stops at
 * the first error the step does not allow. It asserts nothing.
 */
final class DriveRunner {

    /** The variables every drive of a session shares; {@code drive.*} and captures are the runner's own. */
    interface Variables {
        /** The value of a variable of section 12's table, or null when it has none here. */
        JsonNode resolve(String name) throws IOException, InterruptedException;
    }

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]*)}");

    private final AdapterConnection client;
    private final SessionClient session;
    private final Variables variables;
    private final Duration timeout;
    private final boolean pauses;

    DriveRunner(AdapterConnection client, SessionClient session, Variables variables, Duration timeout, boolean pauses) {
        this.client = client;
        this.session = session;
        this.variables = variables;
        this.timeout = timeout;
        this.pauses = pauses;
    }

    /** Whether a drive can start: the client has its operations, and every variable it names has a value. */
    String cannotStart(RuleDefinition rule, Hello hello) throws IOException, InterruptedException {
        for (DriveStep step : rule.drive()) {
            if (step.isOperation() && !hello.implementsOperation(step.op())) {
                return "the client does not implement " + step.op();
            }
        }
        for (DriveStep step : rule.drive()) {
            for (String s : strings(step.args(), new ArrayList<>())) {
                Matcher m = VARIABLE.matcher(s);
                while (m.find()) {
                    String v = m.group(1);
                    if (v.startsWith("service.") && variables.resolve(v) == null) {
                        return "the storage describes no " + v.substring("service.".length());
                    }
                }
            }
        }
        return null;
    }

    /**
     * Runs the drive, its own variables bound.
     *
     * @param container the container the client created for this drive ({@code drive.container})
     * @param inbox     this drive's inbox ({@code drive.inbox})
     */
    DriveNote run(RuleDefinition rule, String container, String inbox) throws IOException, InterruptedException {
        Map<String, JsonNode> bound = new HashMap<>();
        bound.put("drive.container", TextNode.valueOf(container));
        bound.put("drive.inbox", TextNode.valueOf(inbox));
        boolean taskStarted = false;
        boolean taskStep = rule.drive().stream().anyMatch(DriveStep::startTask);
        if (rule.task() != null && !taskStep) {
            session.startTask(rule.name());
            taskStarted = true;
        }
        int i = 0;
        for (DriveStep step : rule.drive()) {
            i++;
            if (step.startTask()) {
                if (!taskStarted) {
                    session.startTask(rule.name());
                    taskStarted = true;
                }
                continue;
            }
            if (step.waitSeconds() > 0) {
                if (pauses) {
                    Thread.sleep(step.waitSeconds() * 1000L);
                }
                continue;
            }
            Unresolved unresolved = new Unresolved();
            JsonNode args = Templates.expandJson(step.args(), expression -> resolve(expression, bound, unresolved));
            if (unresolved.name != null) {
                return DriveNote.stopped(rule.name(), i, "${" + unresolved.name + "} has no value");
            }
            Reply reply = client.call(step.op(), (ObjectNode) args, timeout);
            if (!reply.ok()) {
                Reply.Error e = reply.error();
                if (e.status() != null && step.allows(e.status())) {
                    continue;
                }
                return DriveNote.stopped(rule.name(), i, step.op() + " ended in " + e.describe());
            }
            for (Map.Entry<String, String> c : step.capture().entrySet()) {
                JsonNode value = reply.result().at(c.getValue());
                if (value.isMissingNode()) {
                    return DriveNote.stopped(rule.name(), i, c.getValue() + " selects nothing in the result of " + step.op());
                }
                bound.put(c.getKey(), value);
            }
        }
        return DriveNote.completed(rule.name());
    }

    private static List<String> strings(JsonNode node, List<String> out) {
        if (node.isTextual()) {
            out.add(node.asText());
        } else if (node.isContainerNode()) {
            node.forEach(child -> strings(child, out));
        }
        return out;
    }

    private static final class Unresolved {
        String name;
    }

    private JsonNode resolve(String expression, Map<String, JsonNode> bound, Unresolved unresolved) {
        if (expression.equals("uuid")) {
            return TextNode.valueOf(UUID.randomUUID().toString());
        }
        JsonNode value = bound.get(expression);
        if (value == null) {
            try {
                value = variables.resolve(expression);
            } catch (IOException e) {
                value = null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                value = null;
            }
        }
        if (value == null) {
            unresolved.name = unresolved.name == null ? expression : unresolved.name;
            return TextNode.valueOf("");
        }
        return value;
    }
}
