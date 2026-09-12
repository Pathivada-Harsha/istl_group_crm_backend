package com.istlgroup.istl_group_crm_backend.service.infrastructure;

/**
 * One column-run of text pulled off a single visual line of the Harmonized
 * Master List PDF table: a maximal run of glyphs on that line with no
 * horizontal gap wider than {@link InfrastructureMasterListPdfExtractor}'s
 * column threshold. A table row with "Sr No", "Category" and a bullet item
 * all on the same baseline yields three separate runs, each carrying its own
 * {@code minX} — that per-run x is what lets the parser tell the Category
 * column apart from the Infrastructure sub-sectors column regardless of how
 * a reading-order-only text dump interleaves them at a page break.
 */
public record ExtractedRun(int page, float minX, float y, String text) {
}
