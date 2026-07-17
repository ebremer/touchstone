package com.ebremer.touchstone.mcp.tool;

import java.util.List;
import java.util.Set;

import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.coverage.CoverageReport;
import com.ebremer.touchstone.core.manifest.Manifest;
import com.ebremer.touchstone.mcp.config.Catalog;
import com.ebremer.touchstone.mcp.dto.Dtos.CoverageCell;
import com.ebremer.touchstone.mcp.dto.Dtos.CoverageReportDto;
import com.ebremer.touchstone.mcp.dto.Dtos.RequirementDetail;
import com.ebremer.touchstone.mcp.dto.Dtos.RequirementSummary;
import com.ebremer.touchstone.mcp.manifest.Manifests;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/** Read-only tools over the requirements catalog (DESIGN.md paragraph 6). */
@Service
public class RequirementTools {

    private final Catalog catalog;
    private final Manifests manifests;

    public RequirementTools(Catalog catalog, Manifests manifests) {
        this.catalog = catalog;
        this.manifests = manifests;
    }

    @McpTool(name = "list_requirements",
            description = "List catalog requirements as metadata, optionally filtered by spec module "
                    + "(e.g. lws10-core, lws10-authn-openid) and/or level (MUST, SHOULD, MAY).")
    public List<RequirementSummary> listRequirements(
            @McpToolParam(required = false, description = "spec module key") String module,
            @McpToolParam(required = false, description = "MUST, SHOULD, or MAY") String level) {
        return catalog.all().stream()
                .filter(r -> module == null || module.isBlank() || module.equals(r.specModule()))
                .filter(r -> level == null || level.isBlank() || level.equalsIgnoreCase(r.level()))
                .map(r -> new RequirementSummary(r.iri(), r.level(), r.specModule(), r.section(), r.summary()))
                .toList();
    }

    @McpTool(name = "get_requirement",
            description = "Full detail for one requirement IRI, including the verbatim spec clause text "
                    + "and the section link, so you can read why a test exists.")
    public RequirementDetail getRequirement(
            @McpToolParam(description = "requirement IRI from list_requirements") String iri) {
        Requirement r = catalog.find(iri).orElseThrow(
                () -> new IllegalArgumentException("unknown requirement IRI: " + iri));
        return new RequirementDetail(r.iri(), r.level(), r.specModule(), r.section(), r.status(),
                r.summary(), r.clauseText());
    }

    @McpTool(name = "coverage",
            description = "Requirements-by-tests coverage matrix, per spec module and level, optionally "
                    + "scoped to one module.")
    public CoverageReportDto coverage(
            @McpToolParam(required = false, description = "spec module key") String module) {
        List<Requirement> requirements = catalog.all().stream()
                .filter(r -> module == null || module.isBlank() || module.equals(r.specModule()))
                .toList();
        Set<String> covered = manifests.all().stream()
                .map(Manifest::requirements)
                .flatMap(List::stream)
                .collect(java.util.stream.Collectors.toSet());
        CoverageReport report = CoverageReport.compute(requirements, covered);
        List<CoverageCell> cells = report.rows().stream()
                .map(row -> new CoverageCell(row.specModule(), row.level(), row.covered(), row.total()))
                .toList();
        return new CoverageReportDto(report.totalCovered(), report.totalRequirements(), cells);
    }
}
