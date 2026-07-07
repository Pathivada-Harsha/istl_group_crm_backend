package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * All variable content for generating a SESOLA Purchase Order document.
 * Static content (terms & conditions, company header, signatory block) is fixed
 * in the generator; everything that changes per PO is supplied here.
 */
@Data
public class PoDocumentRequest {

    // PO meta
    private String poNo;
    // Document heading rendered at the top of the PDF. "PURCHASE ORDER" (default) or "WORK ORDER".
    private String docTitle;
    private String poDate;        // pre-formatted display string e.g. "11/06/2026"
    private String quoteRef;      // "NA" etc.
    private String quoteRefDate;

    // Vendor (To) block
    private String vendorName;
    private String vendorAddress; // multi-line; split on newlines
    private String supplierGst;
    private String kindAttention;
    private String vendorPhone;
    private String vendorEmail;

    // Bill-To and Ship-To (default to SESOLA, but editable per PO)
    private String billToName;
    private String billToAddress;
    private String shipToName;
    private String shipToAddress;
    private String shipToPhone;

    // Line items
    private List<PoLineItem> items;

    // Tax & totals
    private Double gstPercent;    // e.g. 18
    private Double gstAmount;     // computed or supplied
    private Double totalAmount;   // grand total incl. GST

    // Optional adjustment lines (e.g. "DC Cable difference amount", "Amount to be paid")
    private List<PoAdjustmentLine> adjustments;

    // Vendor bank details
    private String bankAccountName;
    private String bankName;
    private String bankBranch;
    private String bankAccountNo;
    private String bankIfsc;

    // Signatory (usually constant; overridable)
    private String signatoryName;
    private String signatoryTitle;
    private String signatoryPhone;

    // ── Editable "from" (SESOLA) side — override the hardcoded defaults when supplied ──
    private String companyName;
    private String companyAddress;
    private String companyGst;
    private String companyContact;   // full contact block (person/email/phone), multi-line

    // Editable terms & conditions (list of lines). When null/empty, defaults are used.
    private java.util.List<String> terms;

    // Which stamp file to use from the po-assets library (e.g. "sesola_stamp.png").
    private String stampFileName;

    @Data
    public static class PoLineItem {
        @JsonProperty("sNo")
        private Integer sNo;
        private String description;
        private String hsnCode;   // retained for backward compatibility; no longer rendered on the PO PDF
        private String unit;      // e.g. Nos, Kg, Set — replaces HSN column on the PDF
        private Double qty;
        private Double pricePerUnit;
        private Double amount;
    }

    @Data
    public static class PoAdjustmentLine {
        private String label;     // e.g. "DC Cable difference amount"
        private Double amount;
    }
}