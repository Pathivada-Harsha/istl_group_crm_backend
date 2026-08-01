// ─────────────────────────────────────────────────────────────────────────────
// PROVISIONAL FEATURE — "Orders in Line"
// Temporary stopgap register, scheduled for replacement by a permanent pipeline
// module. Data here migrates into the leads table at that point.
// Removal: drop table `orders_in_line`, delete the OrdersInLine* files, revert the
// two lines in Dashboard.js, the sidebar entry, and the App.js import + route.
// ─────────────────────────────────────────────────────────────────────────────
package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.OrdersInLineEntity;
import com.istlgroup.istl_group_crm_backend.repo.OrdersInLineRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrdersInLineWrapper;

/**
 * CRUD + dashboard summary for the provisional Orders-in-Line register.
 *
 * <p>Validation is imperative and signals via {@link CustomException}, matching
 * the house style (bean validation is not used anywhere in this codebase).
 * Deliberately permissive: only the client name is required, because records
 * are captured in a hurry from phone calls and a form that blocks on missing
 * detail will not get used.
 */
@Service
public class OrdersInLineService {

    @Autowired
    private OrdersInLineRepo repo;

    /**
     * The only values {@code status} may ever hold. Enforced here rather than by
     * a DB enum so a direct API call cannot store anything else either.
     */
    public static final List<String> STATUSES = List.of(
            "Enquiry Received", "In Discussion", "Quoted", "Confirmed", "On Hold", "Dropped");

    public static final String DEFAULT_STATUS = "Enquiry Received";

    /** "Open" = everything still in play. Used by the dashboard summary. */
    private static final List<String> CLOSED_STATUSES = List.of("Confirmed", "Dropped");

    /**
     * Power-unit → kW multipliers for the summary roll-up. Anything else (Units,
     * Kg, Km …) contributes nothing to a capacity total and is skipped. Kept
     * local rather than calling CapacityUtil so removing this feature drops no
     * shared dependency.
     */
    private static final Map<String, BigDecimal> KW_FACTORS = Map.of(
            "kw",  BigDecimal.ONE,
            "kwp", BigDecimal.ONE,
            "mw",  new BigDecimal("1000"),
            "mwp", new BigDecimal("1000"));

    // ── reads ────────────────────────────────────────────────────────────

    /**
     * Filtered list, newest received date first. Every filter is optional and
     * they combine with AND; soft-deleted rows are never returned.
     */
    @Transactional(readOnly = true)
    public List<OrdersInLineWrapper> list(String search, String status, String category,
                                          String fromDate, String toDate) throws CustomException {
        LocalDate from = parseDate(blankToNull(fromDate), "receivedDateFrom");
        LocalDate to   = parseDate(blankToNull(toDate),   "receivedDateTo");

        List<OrdersInLineWrapper> out = new ArrayList<>();
        for (OrdersInLineEntity e : repo.search(blankToNull(search), blankToNull(status),
                                                blankToNull(category), from, to)) {
            out.add(toWrapper(e));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public OrdersInLineWrapper getById(Long id) throws CustomException {
        return toWrapper(repo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Order in line not found")));
    }

    /**
     * Small aggregate for the dashboard block only. Deliberately its own
     * endpoint — the existing admin dashboard response is not extended, so
     * removing this feature never touches the dashboard service or DTO.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        for (String s : STATUSES) statusCounts.put(s, 0);

        int openCount = 0;
        BigDecimal openValue = BigDecimal.ZERO;
        BigDecimal openKw    = BigDecimal.ZERO;

        for (OrdersInLineEntity e : repo.findByDeletedAtIsNull()) {
            String status = e.getStatus();
            if (status != null && statusCounts.containsKey(status)) {
                statusCounts.put(status, statusCounts.get(status) + 1);
            }
            if (status != null && CLOSED_STATUSES.contains(status)) continue;

            openCount++;
            if (e.getEstimatedValue() != null) openValue = openValue.add(e.getEstimatedValue());
            BigDecimal kw = toKw(e.getCapacity(), e.getCapacityUnit());
            if (kw != null) openKw = openKw.add(kw);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("openCount", openCount);
        data.put("statusCounts", statusCounts);
        data.put("openEstimatedValue", openValue.toPlainString());
        data.put("openCapacityKw", openKw.toPlainString());
        return data;
    }

    // ── writes ───────────────────────────────────────────────────────────

    @Transactional
    public OrdersInLineWrapper create(OrdersInLineWrapper w, Long userId) throws CustomException {
        OrdersInLineEntity e = new OrdersInLineEntity();
        applyScalars(e, w);

        // Server-side defaults — a record typed off a phone call may carry neither.
        if (blankToNull(w.getStatus()) == null) e.setStatus(DEFAULT_STATUS);
        if (e.getReceivedDate() == null)        e.setReceivedDate(LocalDate.now());

        e.setCreatedBy(userId);
        return toWrapper(repo.save(e));
    }

    @Transactional
    public OrdersInLineWrapper update(Long id, OrdersInLineWrapper w) throws CustomException {
        // findByIdAndDeletedAtIsNull — a soft-deleted record cannot be resurrected.
        OrdersInLineEntity e = repo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Order in line not found"));
        applyScalars(e, w);
        return toWrapper(repo.save(e));
    }

    /** Soft delete only — never a hard delete, and there is no restore path. */
    @Transactional
    public void delete(Long id) throws CustomException {
        OrdersInLineEntity e = repo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Order in line not found"));
        e.setDeletedAt(LocalDateTime.now());
        repo.save(e);
    }

    // ── mapping + validation ─────────────────────────────────────────────

    private void applyScalars(OrdersInLineEntity e, OrdersInLineWrapper w) throws CustomException {
        String clientName = blankToNull(w.getClientName());
        if (clientName == null) throw new CustomException("Client name is required");

        e.setClientName(clip(clientName, 255));
        e.setSourceParty(clip(w.getSourceParty(), 255));
        e.setSourceType(clip(w.getSourceType(), 50));

        e.setCapacity(clip(validateCapacity(w.getCapacity()), 50));
        e.setCapacityUnit(clip(w.getCapacityUnit(), 20));
        e.setCapacityType(validateCapacityType(w.getCapacityType()));

        e.setCategory(clip(w.getCategory(), 150));
        e.setState(clip(w.getState(), 100));
        e.setDistrict(clip(w.getDistrict(), 100));

        e.setContactPerson(clip(w.getContactPerson(), 255));
        e.setPhone(clip(w.getPhone(), 30));
        e.setEmail(clip(w.getEmail(), 255));

        e.setEstimatedValue(validateEstimatedValue(w.getEstimatedValue()));
        e.setReceivedDate(parseDate(blankToNull(w.getReceivedDate()), "receivedDate"));
        e.setExpectedDecisionDate(parseDate(blankToNull(w.getExpectedDecisionDate()), "expectedDecisionDate"));

        String status = blankToNull(w.getStatus());
        if (status != null) {
            if (!STATUSES.contains(status)) {
                throw new CustomException("Invalid status: " + status
                        + ". Must be one of " + String.join(", ", STATUSES));
            }
            e.setStatus(status);
        }

        e.setOwnerUserId(w.getOwnerUserId());
        e.setRemarks(w.getRemarks());
    }

    private OrdersInLineWrapper toWrapper(OrdersInLineEntity e) {
        OrdersInLineWrapper w = new OrdersInLineWrapper();
        w.setId(e.getId());
        w.setClientName(e.getClientName());
        w.setSourceParty(e.getSourceParty());
        w.setSourceType(e.getSourceType());
        w.setCapacity(e.getCapacity());
        w.setCapacityUnit(e.getCapacityUnit());
        w.setCapacityType(e.getCapacityType());
        w.setCategory(e.getCategory());
        w.setState(e.getState());
        w.setDistrict(e.getDistrict());
        w.setContactPerson(e.getContactPerson());
        w.setPhone(e.getPhone());
        w.setEmail(e.getEmail());
        w.setEstimatedValue(s(e.getEstimatedValue()));
        w.setReceivedDate(s(e.getReceivedDate()));
        w.setExpectedDecisionDate(s(e.getExpectedDecisionDate()));
        w.setStatus(e.getStatus());
        w.setOwnerUserId(e.getOwnerUserId());
        w.setRemarks(e.getRemarks());
        w.setCreatedBy(e.getCreatedBy());
        w.setCreatedAt(s(e.getCreatedAt()));
        w.setUpdatedAt(s(e.getUpdatedAt()));
        return w;
    }

    /** AC / DC, case-insensitive on the way in and normalised on the way to the DB. */
    private String validateCapacityType(String raw) throws CustomException {
        String v = blankToNull(raw);
        if (v == null) return null;
        String upper = v.toUpperCase();
        if (!upper.equals("AC") && !upper.equals("DC")) {
            throw new CustomException("Capacity type must be AC or DC");
        }
        return upper;
    }

    /** Capacity is stored as text (matching leads) but must still be a positive number. */
    private String validateCapacity(String raw) throws CustomException {
        String v = blankToNull(raw);
        if (v == null) return null;
        BigDecimal n;
        try {
            n = new BigDecimal(v);
        } catch (NumberFormatException ex) {
            throw new CustomException("Capacity must be a number");
        }
        if (n.compareTo(BigDecimal.ZERO) <= 0) throw new CustomException("Capacity must be greater than zero");
        return v;
    }

    private BigDecimal validateEstimatedValue(String raw) throws CustomException {
        String v = blankToNull(raw);
        if (v == null) return null;
        BigDecimal n;
        try {
            n = new BigDecimal(v);
        } catch (NumberFormatException ex) {
            throw new CustomException("Estimated value must be a number");
        }
        if (n.compareTo(BigDecimal.ZERO) < 0) throw new CustomException("Estimated value cannot be negative");
        return n;
    }

    /** Null-safe kW conversion for the summary; returns null for non-power units. */
    private BigDecimal toKw(String capacity, String unit) {
        if (blankToNull(capacity) == null || blankToNull(unit) == null) return null;
        BigDecimal factor = KW_FACTORS.get(unit.trim().toLowerCase());
        if (factor == null) return null;
        try {
            return new BigDecimal(capacity.trim()).multiply(factor);
        } catch (NumberFormatException ex) {
            return null;   // legacy/garbage value — never break the dashboard over it
        }
    }

    private LocalDate parseDate(String v, String fieldName) throws CustomException {
        if (v == null) return null;
        try { return LocalDate.parse(v); } catch (Exception ignored) { }
        try { return OffsetDateTime.parse(v).toLocalDate(); } catch (Exception ignored) { }
        throw new CustomException("Invalid date for " + fieldName + ": " + v + " (expected yyyy-MM-dd)");
    }

    private String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private String clip(String v, int max) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max).trim();
    }

    private String s(BigDecimal b)    { return b == null ? null : b.toPlainString(); }
    private String s(LocalDate d)     { return d == null ? null : d.toString(); }
    private String s(LocalDateTime d) { return d == null ? null : d.toString(); }
}
