package com.istlgroup.istl_group_crm_backend.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.BorrowerSanctionEntity;
import com.istlgroup.istl_group_crm_backend.service.BorrowerService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BorrowerSanctionWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BorrowerWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.CompanyGroupWrapper;

/**
 * Borrower Registry REST API. Follows the TenderController conventions: a
 * {@code {success, message, data}} envelope and the {@code User-Id} header for
 * the current user. Protected by SessionFilter like every non-login endpoint,
 * so no security config change is needed.
 */
@RestController
@RequestMapping("/borrower")
public class BorrowerController {

    private static final Logger log = LoggerFactory.getLogger(BorrowerController.class);

    @Autowired
    private BorrowerService borrowerService;

    // ── borrowers ───────────────────────────────────────────────────────────

    @GetMapping("/getAll")
    public ResponseEntity<Map<String, Object>> getAll(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "category", required = false) String category) {
        try {
            List<BorrowerWrapper> data = borrowerService.getAll(userId, userRole, search, category);
            return ok(data, null);
        } catch (Exception e) {
            log.error("Failed to load borrowers", e);
            return error("Failed to load borrowers: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Categories present across live sanctions. Served from the database rather
     * than derived on the client, so the dropdown lists every category on file
     * instead of only those on the rows currently displayed.
     */
    @GetMapping("/categories")
    public ResponseEntity<Map<String, Object>> categories() {
        try {
            return ok(borrowerService.getCategories(), null);
        } catch (Exception e) {
            log.error("Failed to load categories", e);
            return error("Failed to load categories", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @PathVariable Long id) {
        try {
            return ok(borrowerService.getById(userId, userRole, id), null);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return error("Failed to load borrower: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody BorrowerWrapper body,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        try {
            return ok(borrowerService.createBorrower(body, userId), "Borrower created");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (DataIntegrityViolationException e) {
            return error(friendlyDataError(e), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to create borrower: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/update/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody BorrowerWrapper body,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            return ok(borrowerService.updateBorrower(id, body, userId, userRole), "Borrower updated");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (DataIntegrityViolationException e) {
            return error(friendlyDataError(e), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to update borrower: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @DeleteMapping("/delete/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable Long id,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            borrowerService.deleteBorrower(id, userId, userRole);
            return ok(null, "Borrower deleted");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to delete borrower: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Find-or-create by name, used by the review screen before saving.
     *
     * <p>Takes a whole {@link BorrowerWrapper} rather than just the name so the
     * borrower-level values the letter carried — promoter, guarantor, group,
     * Cat / Sub Cat, SL ref. — aren't dropped on the floor. The older
     * {@code {"borrowerName": "..."}} payload still deserialises unchanged.
     */
    @PostMapping("/resolve")
    public ResponseEntity<Map<String, Object>> resolve(
            @RequestBody BorrowerWrapper body,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        try {
            return ok(borrowerService.resolveBorrower(body, userId), null);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (DataIntegrityViolationException e) {
            return error(friendlyDataError(e), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to resolve borrower: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Atomic resolve + hierarchy-placement for the sanction-import "confirm
     * this company" step — see
     * {@link com.istlgroup.istl_group_crm_backend.service.BorrowerService#resolveBorrowerWithHierarchy}
     * for why {@code resolve()} + {@code /{id}/hierarchy} as two separate
     * calls was a data-integrity problem (2026-09-02 save-flow atomicity fix).
     */
    @PostMapping("/resolve-with-hierarchy")
    public ResponseEntity<Map<String, Object>> resolveWithHierarchy(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            BorrowerWrapper identity = mapper.convertValue(body.get("identity"), BorrowerWrapper.class);
            Long parentGroupId = body.get("parentGroupId") == null ? null
                    : Long.valueOf(String.valueOf(body.get("parentGroupId")));
            String newParentGroupName = body.get("newParentGroupName") == null
                    ? null : String.valueOf(body.get("newParentGroupName"));
            String newParentGroupCin = body.get("newParentGroupCin") == null
                    ? null : String.valueOf(body.get("newParentGroupCin"));
            String newParentGroupAddress = body.get("newParentGroupAddress") == null
                    ? null : String.valueOf(body.get("newParentGroupAddress"));
            Long subGroupId = body.get("subGroupId") == null ? null
                    : Long.valueOf(String.valueOf(body.get("subGroupId")));
            String newSubGroupName = body.get("newSubGroupName") == null
                    ? null : String.valueOf(body.get("newSubGroupName"));
            String newSubGroupCin = body.get("newSubGroupCin") == null
                    ? null : String.valueOf(body.get("newSubGroupCin"));
            String newSubGroupAddress = body.get("newSubGroupAddress") == null
                    ? null : String.valueOf(body.get("newSubGroupAddress"));
            Boolean isSubsidiary = body.get("isSubsidiary") == null ? null
                    : Boolean.valueOf(String.valueOf(body.get("isSubsidiary")));
            Boolean isSpv = body.get("isSpv") == null ? null
                    : Boolean.valueOf(String.valueOf(body.get("isSpv")));
            return ok(borrowerService.resolveBorrowerWithHierarchy(identity,
                    parentGroupId, newParentGroupName, newParentGroupCin, newParentGroupAddress,
                    subGroupId, newSubGroupName, newSubGroupCin, newSubGroupAddress,
                    isSubsidiary, isSpv, userId), null);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (DataIntegrityViolationException e) {
            return error(friendlyDataError(e), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to resolve borrower: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Move a company between groups, or in/out of standalone. Never touches its sanctions. */
    @PutMapping("/{id}/hierarchy")
    public ResponseEntity<Map<String, Object>> updateHierarchy(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Long groupId = body.get("groupId") == null ? null
                    : Long.valueOf(String.valueOf(body.get("groupId")));
            Boolean isSubsidiary = body.get("isSubsidiary") == null ? null
                    : Boolean.valueOf(String.valueOf(body.get("isSubsidiary")));
            Boolean isSpv = body.get("isSpv") == null ? null
                    : Boolean.valueOf(String.valueOf(body.get("isSpv")));
            return ok(borrowerService.updateBorrowerHierarchy(id, groupId, isSubsidiary, isSpv, userId, userRole),
                    "Organization updated");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to update the organization: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Rank candidate borrowers for a parsed sanction letter's identity —
     * CIN, then normalized name, then alias, then a surfaced-only fuzzy
     * match. Called by the review screen before {@code /borrower/resolve} or
     * {@code /borrower/create}, so the lender confirms which company (or
     * that it's a new one) before anything is written.
     */
    @PostMapping("/match")
    public ResponseEntity<Map<String, Object>> match(@RequestBody BorrowerWrapper body) {
        try {
            return ok(borrowerService.matchBorrower(body), null);
        } catch (Exception e) {
            return error("Failed to match borrower: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── company hierarchy — Parent Groups / Sub Groups ─────────────────────

    /** Top-level Parent Groups when parentId is absent, else the Sub Groups under it. */
    @GetMapping("/groups")
    public ResponseEntity<Map<String, Object>> listGroups(
            @RequestParam(value = "parentId", required = false) Long parentId) {
        try {
            return ok(borrowerService.listGroups(parentId), null);
        } catch (Exception e) {
            return error("Failed to load groups: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/groups/search")
    public ResponseEntity<Map<String, Object>> searchGroups(
            @RequestParam(value = "q", required = false) String q) {
        try {
            return ok(borrowerService.searchGroups(q), null);
        } catch (Exception e) {
            return error("Failed to search groups: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/groups")
    public ResponseEntity<Map<String, Object>> createGroup(
            @RequestBody CompanyGroupWrapper body,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        try {
            return ok(borrowerService.createGroup(body, userId), "Group created");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (DataIntegrityViolationException e) {
            return error(friendlyDataError(e), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to create group: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/groups/{id}")
    public ResponseEntity<Map<String, Object>> updateGroup(
            @PathVariable Long id,
            @RequestBody CompanyGroupWrapper body,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        try {
            return ok(borrowerService.updateGroup(id, body, userId), "Group updated");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (DataIntegrityViolationException e) {
            return error(friendlyDataError(e), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to update group: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Deletes a Parent Group or Sub Group and everything under it — every
     * company in it (with its sanctions and documents) and, for a Parent
     * Group, every Sub Group beneath it too. Irreversible; the caller is
     * expected to have already confirmed with the user.
     */
    @DeleteMapping("/groups/{id}")
    public ResponseEntity<Map<String, Object>> deleteGroup(
            @PathVariable Long id,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        try {
            borrowerService.deleteGroup(id, userId);
            return ok(null, "Group deleted");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to delete group: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * The registry's Level‑1 list — top-level Parent Groups and standalone
     * companies. With no {@code page}/{@code size}, returns the full
     * Group -> Sub Group -> Company tree exactly as before (kept for
     * backward compatibility); with them, returns just that page's rows
     * plus registry-wide stats that don't shrink to match the page.
     */
    @GetMapping("/hierarchy")
    public ResponseEntity<Map<String, Object>> hierarchy(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "search", required = false) String search) {
        try {
            if (page == null || size == null) {
                return ok(borrowerService.getHierarchyTree(userId, userRole), null);
            }
            return ok(borrowerService.getHierarchyPage(userId, userRole, page, size, search), null);
        } catch (Exception e) {
            return error("Failed to load the hierarchy: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** One Parent Group or Sub Group's own summary — breadcrumb and stat-card figures, no nested rows. */
    @GetMapping("/groups/{id}")
    public ResponseEntity<Map<String, Object>> groupDetail(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @PathVariable Long id) {
        try {
            return ok(borrowerService.getGroupDetail(userId, userRole, id), null);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to load the group: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** One page of the companies sitting directly under a group — Direct Companies, or one Sub Group's own table. */
    @GetMapping("/groups/{id}/companies")
    public ResponseEntity<Map<String, Object>> groupCompanies(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size) {
        try {
            return ok(borrowerService.getGroupCompanies(userId, userRole, id, page, size), null);
        } catch (Exception e) {
            return error("Failed to load companies: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** One page of the Sub Groups sitting directly under a Parent Group. */
    @GetMapping("/groups/{id}/subgroups")
    public ResponseEntity<Map<String, Object>> subGroups(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size) {
        try {
            return ok(borrowerService.getSubGroups(userId, userRole, id, page, size), null);
        } catch (Exception e) {
            return error("Failed to load Sub Groups: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Sanctions associated directly with this Parent Group or Sub Group — never a child company's own. */
    @GetMapping("/groups/{id}/sanctions")
    public ResponseEntity<Map<String, Object>> groupSanctions(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @PathVariable Long id) {
        try {
            return ok(borrowerService.listGroupSanctions(userId, userRole, id), null);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to load the group's sanctions: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Save a sanction letter associated directly with this Parent Group or Sub Group, not any company. */
    @PostMapping("/groups/{id}/sanction/save")
    public ResponseEntity<Map<String, Object>> saveGroupSanction(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            BorrowerSanctionWrapper sanction =
                    mapper.convertValue(body.get("sanction"), BorrowerSanctionWrapper.class);
            String rawJson = body.get("rawExtracted") == null
                    ? null : mapper.writeValueAsString(body.get("rawExtracted"));
            return ok(borrowerService.saveGroupSanction(sanction, id, userId, userRole, rawJson), "Sanction saved");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (DataIntegrityViolationException e) {
            return error(friendlyDataError(e), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to save sanction: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── import ──────────────────────────────────────────────────────────────

    /**
     * Stateless parse. Reads the letter and returns a field map for the review
     * screen; writes nothing, so it works before a borrower exists.
     */
    @PostMapping(value = "/parse-sanction", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> parseSanction(
            @RequestParam("file") MultipartFile file) {
        try {
            return ok(borrowerService.parseSanction(file), null);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to read the document: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── sanctions ───────────────────────────────────────────────────────────

    @PostMapping("/sanction/save")
    public ResponseEntity<Map<String, Object>> saveSanction(
            @RequestBody Map<String, Object> body,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            BorrowerSanctionWrapper sanction =
                    mapper.convertValue(body.get("sanction"), BorrowerSanctionWrapper.class);

            String rawJson = body.get("rawExtracted") == null
                    ? null : mapper.writeValueAsString(body.get("rawExtracted"));

            // A reviewer's correction to a misread CIN/registered address —
            // applied to the borrower in the SAME transaction as the
            // sanction save (see BorrowerService#saveSanction), not a
            // separate call.
            String identityCin = body.get("identityCin") == null
                    ? null : String.valueOf(body.get("identityCin"));
            String identityRegisteredAddress = body.get("identityRegisteredAddress") == null
                    ? null : String.valueOf(body.get("identityRegisteredAddress"));

            return ok(borrowerService.saveSanction(sanction, identityCin, identityRegisteredAddress,
                    userId, userRole, rawJson), "Sanction saved");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (DataIntegrityViolationException e) {
            return error(friendlyDataError(e), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to save sanction: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Soft duplicate check ahead of save — same lender and sanction date on
     * the same company. Always advisory; the hard block on a reused ref. no.
     * already happens inside {@code /borrower/parse-sanction} and again in
     * {@code /borrower/sanction/save}.
     */
    @GetMapping("/sanction/check-duplicate")
    public ResponseEntity<Map<String, Object>> checkDuplicateSanction(
            @RequestParam("borrowerId") Long borrowerId,
            @RequestParam("lenderName") String lenderName,
            @RequestParam("sanctionDate") String sanctionDate) {
        try {
            return ok(borrowerService.checkDuplicateSanction(borrowerId, lenderName, sanctionDate), null);
        } catch (Exception e) {
            return error("Failed to check for duplicates: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @DeleteMapping("/sanction/delete/{id}")
    public ResponseEntity<Map<String, Object>> deleteSanction(
            @PathVariable Long id,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            borrowerService.deleteSanction(id, userId, userRole);
            return ok(null, "Sanction deleted");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to delete sanction: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** The one editable Active/Inactive control — changes only this sanction's own status; see BorrowerService#updateSanctionStatus. */
    @PutMapping("/sanction/{id}/status")
    public ResponseEntity<Map<String, Object>> updateSanctionStatus(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            String activeStatus = body.get("activeStatus") == null ? null : String.valueOf(body.get("activeStatus"));
            return ok(borrowerService.updateSanctionStatus(id, activeStatus, userId, userRole), "Status updated");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to update status: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── the letter ──────────────────────────────────────────────────────────

    @PostMapping(value = "/sanction/{id}/upload-doc", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadDoc(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            return ok(borrowerService.uploadDocument(id, file, userId, userRole), "Document stored");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to store the document: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * In-page preview. PDFs are served by the download endpoint and rendered in
     * an iframe; Word files can't be, so they come back here as sanitised HTML
     * the viewer drops into the page. Either way the user never has to download
     * a file just to read it.
     */
    @GetMapping("/sanction/{id}/preview-doc")
    public ResponseEntity<Map<String, Object>> previewDoc(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @PathVariable Long id) {
        try {
            Map<String, Object> payload = borrowerService.buildPreview(userId, userRole, id);
            return ok(payload, null);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Throwable t) {
            // Catch Throwable, not Exception: POI raises Errors of its own
            // (NoClassDefFoundError when a schema jar is missing, OOM on a
            // huge file) and those would otherwise reach the global handler,
            // which replaces the cause with "Something went wrong" and logs
            // nothing — leaving no way to tell what actually failed.
            log.error("Preview failed for sanction {}", id, t);
            String cause = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            return error("Could not render this document (" + cause
                       + "). You can still download it.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Serve the stored letter. Defaults to inline so a PDF opens in the viewer;
     * pass forceDownload=true for the download action.
     */
    @GetMapping("/sanction/{id}/download-doc")
    public ResponseEntity<byte[]> downloadDoc(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @PathVariable Long id,
            @RequestParam(value = "forceDownload", defaultValue = "false") boolean forceDownload) {
        try {
            BorrowerSanctionEntity s = borrowerService.getDocumentEntity(userId, userRole, id);
            String mime = s.getSanctionDocMime() != null
                    ? s.getSanctionDocMime() : "application/octet-stream";
            String fileName = s.getSanctionDocName() != null
                    ? s.getSanctionDocName() : "sanction-letter";
            boolean inline = !forceDownload && mime.toLowerCase().contains("pdf");
            String disposition = (inline ? "inline" : "attachment")
                    + "; filename=\"" + fileName + "\"";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .contentType(MediaType.parseMediaType(mime))
                    .contentLength(s.getSanctionDocData().length)
                    .body(s.getSanctionDocData());
        } catch (CustomException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ── error translation ───────────────────────────────────────────────────

    private static final Pattern TOO_LONG_COLUMN = Pattern.compile("column '([a-zA-Z_]+)'");

    /**
     * Hibernate/JDBC exceptions carry the failed SQL statement in their
     * message — useful in a log, but meaningless (and a little alarming) shown
     * to a lender. This turns the common cases (a value too long for its
     * column, a unique-constraint hit) into something a user can act on;
     * anything unrecognised still gets a plain "couldn't save" rather than the
     * raw statement.
     */
    private String friendlyDataError(DataIntegrityViolationException e) {
        Throwable root = e.getMostSpecificCause();
        String msg = root == null ? e.getMessage() : root.getMessage();
        String lower = msg == null ? "" : msg.toLowerCase();

        if (lower.contains("data too long") || lower.contains("data truncation")) {
            Matcher m = TOO_LONG_COLUMN.matcher(msg);
            String field = m.find() ? humanizeColumn(m.group(1)) : "one of the fields";
            return "The value entered for \"" + field + "\" is too long. Please shorten it and try again.";
        }
        if (lower.contains("duplicate entry") || lower.contains("unique constraint")) {
            return "That value is already used by another record and must be unique.";
        }
        if (lower.contains("cannot be null") || lower.contains("null not allowed")) {
            return "A required value is missing. Please fill in every required field and try again.";
        }
        log.error("Unrecognised data error while saving", e);
        return "Could not save — one of the values entered doesn't fit. Please check what you entered and try again.";
    }

    private String humanizeColumn(String column) {
        String spaced = column.replace('_', ' ');
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    // ── envelope helpers ────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> ok(Object data, String message) {
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        if (message != null) res.put("message", message);
        res.put("data", data);
        return ResponseEntity.ok(res);
    }

    private ResponseEntity<Map<String, Object>> error(String message, HttpStatus status) {
        Map<String, Object> res = new HashMap<>();
        res.put("success", false);
        res.put("message", message);
        return ResponseEntity.status(status).body(res);
    }
}