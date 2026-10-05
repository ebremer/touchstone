package com.ebremer.touchstone.mcp.tool;

import java.util.List;
import java.util.Set;

import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.coverage.CoverageReport;
import com.ebremer.touchstone.core.definitions.TestDefinition;
import com.ebremer.touchstone.mcp.config.Catalog;
import com.ebremer.touchstone.mcp.dto.Dtos.CoverageCell;
import com.ebremer.touchstone.mcp.dto.Dtos.CoverageReportDto;
import com.ebremer.touchstone.mcp.dto.Dtos.RequirementDetail;
import com.ebremer.touchstone.mcp.dto.Dtos.RequirementSummary;
import com.ebremer.touchstone.mcp.definitions.TestDefinitions;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/**
 * Read-only tools over the requirements catalog (DESIGN.md paragraph 6). Each says so in its
 * MCP annotations: read-only, idempotent, and closed-world, since the catalog is a local file,
 * so a client need not ask before calling it (D-0049).
 */
@Service
public class RequirementTools {

    private final Catalog catalog;
    private final TestDefinitions definitions;

    public RequirementTools(Catalog catalog, TestDefinitions definitions) {
        this.catalog = catalog;
        this.definitions = definitions;
    }

    @McpTool(name = "list_requirements",
            description = "List catalog requirements as metadata, optionally filtered by spec module "
                    + "(e.g. lws10-core, lws10-authn-openid), level (MUST, SHOULD, MAY) and/or the role the "
                    + "clause binds (Server, AuthorizationServer, Client, IdentityProvider, Receiver, "
                    + "Specification).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public List<RequirementSummary> listRequirements(
            @McpToolParam(required = false, description = "spec module key") String module,
            @McpToolParam(required = false, description = "MUST, SHOULD, or MAY") String level,
            @McpToolParam(required = false, description = "a role the clause binds, e.g. Server or Client")
                    String role) {
        return catalog.all().stream()
                .filter(r -> module == null || module.isBlank() || module.equals(r.specModule()))
                .filter(r -> level == null || level.isBlank() || level.equalsIgnoreCase(r.level()))
                .filter(r -> role == null || role.isBlank()
                        || r.appliesTo().stream().anyMatch(role::equalsIgnoreCase))
                .map(r -> new RequirementSummary(r.iri(), r.level(), r.specModule(), r.section(), r.summary(),
                        r.appliesTo()))
                .toList();
    }

    @McpTool(name = "get_requirement",
            description = "Full detail for one requirement IRI, including the verbatim spec clause text "
                    + "and the section link, so you can read why a test exists.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public RequirementDetail getRequirement(
            @McpToolParam(description = "requirement IRI from list_requirements") String iri) {
        Requirement r = catalog.find(iri).orElseThrow(
                () -> new IllegalArgumentException("unknown requirement IRI: " + iri));
        return new RequirementDetail(r.iri(), r.level(), r.specModule(), r.section(), r.status(),
                r.summary(), r.clauseText(), r.appliesTo());
    }

    @McpTool(name = "coverage",
            description = "Requirements-by-tests coverage matrix, per spec module and level, optionally "
                    + "scoped to one module. It counts the requirements a server run answers for, those "
                    + "binding a server or an authorization server; notCounted says how many others there are.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CoverageReportDto coverage(
            @McpToolParam(required = false, description = "spec module key") String module) {
        List<Requirement> inModule = catalog.all().stream()
                .filter(r -> module == null || module.isBlank() || module.equals(r.specModule()))
                .toList();
        List<Requirement> requirements = inModule.stream().filter(Requirement::bindsServerSide).toList();
        Set<String> covered = definitions.all().stream()
                .map(TestDefinition::requirements)
                .flatMap(List::stream)
                .collect(java.util.stream.Collectors.toSet());
        CoverageReport report = CoverageReport.compute(requirements, covered);
        List<CoverageCell> cells = report.rows().stream()
                .map(row -> new CoverageCell(row.specModule(), row.level(), row.covered(), row.total()))
                .toList();
        return new CoverageReportDto(report.totalCovered(), report.totalRequirements(), cells,
                inModule.size() - requirements.size());
    }
}
