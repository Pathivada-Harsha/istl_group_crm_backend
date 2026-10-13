package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.CustomersEntity;
import com.istlgroup.istl_group_crm_backend.entity.TenderEntity;
import com.istlgroup.istl_group_crm_backend.repo.*;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.CustomerRequestWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TenderWrapper;

/**
 * Assigned To between a tender and the client generated from it.
 *
 * The repositories are in-memory fakes (no database): these tests exercise the
 * service logic, the link (customers.source_tender_id) and the access rules.
 * Transaction rollback itself is Spring's job and is only checked for being declared.
 */
class TenderClientAssignmentSyncTest {

    static final long ADMIN = 1, A = 11, B = 12, C = 13, OUTSIDER = 99;

    final Map<Long, TenderEntity> tenders = new HashMap<>();
    final Map<Long, CustomersEntity> customers = new HashMap<>();
    long seq = 100;

    TenderService tenderService;
    CustomersService customersService;
    UserScopeService userScope;
    RuntimeException failCustomerSave;

    /** Everyone with role ADMIN sees everything and may assign to anyone; others are level 5. */
    static final String ADMIN_ROLE = "ADMIN", STAFF_ROLE = "BD_EXECUTIVE";

    @BeforeEach
    void wire() {
        TenderRepo tenderRepo = mock(TenderRepo.class);
        CustomersRepo customersRepo = mock(CustomersRepo.class);
        // Hand-written stubs: Mockito cannot instrument classes on this JDK (interfaces are fine).
        userScope = new UserScopeService(null, null) {
            @Override public boolean canActOn(Long acting, Long target) {
                return target == null || acting.equals(target) || acting == ADMIN;   // admin: anyone; staff: only themselves
            }
            @Override public String displayName(Long id) { return "user#" + id; }
        };
        RoleHierarchyService roles = new RoleHierarchyService() {
            @Override public int getLevelOrder(String role) { return ADMIN_ROLE.equals(role) ? 1 : 5; }
        };
        TeamRepository teams = mock(TeamRepository.class);
        UsersRepo usersRepo = mock(UsersRepo.class);

        RecordAccessPolicy policy = new RecordAccessPolicy();
        ReflectionTestUtils.setField(policy, "roleHierarchyService", roles);
        ReflectionTestUtils.setField(policy, "teamRepository", teams);


        lenient().when(tenderRepo.save(any())).thenAnswer(i -> {
            TenderEntity t = i.getArgument(0);
            if (t.getId() == null) t.setId(++seq);
            tenders.put(t.getId(), t);
            return t;
        });
        lenient().when(tenderRepo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(tenders.get(i.<Long>getArgument(0))));
        lenient().when(tenderRepo.findByIdAndDeletedAtIsNull(anyLong())).thenAnswer(i -> Optional.ofNullable(tenders.get(i.<Long>getArgument(0))));
        lenient().when(tenderRepo.findByIdForUpdate(anyLong())).thenAnswer(i -> Optional.ofNullable(tenders.get(i.<Long>getArgument(0))));

        lenient().when(customersRepo.save(any())).thenAnswer(i -> {
            if (failCustomerSave != null) throw failCustomerSave;
            CustomersEntity c = i.getArgument(0);
            if (c.getId() == null) {
                c.setId(++seq);
                // the unique key on source_tender_id
                if (c.getSourceTenderId() != null) {
                    boolean dup = customers.values().stream().anyMatch(x -> c.getSourceTenderId().equals(x.getSourceTenderId()));
                    assertFalse(dup, "unique(source_tender_id) violated: a second client for the same tender");
                }
            }
            customers.put(c.getId(), c);
            return c;
        });
        lenient().when(customersRepo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(customers.get(i.<Long>getArgument(0))));
        lenient().when(customersRepo.findBySourceTenderId(anyLong())).thenAnswer(i -> customers.values().stream()
                .filter(c -> i.<Long>getArgument(0).equals(c.getSourceTenderId())).findFirst());

        tenderService = new TenderService();
        ReflectionTestUtils.setField(tenderService, "tenderRepo", tenderRepo);
        ReflectionTestUtils.setField(tenderService, "customersRepo", customersRepo);
        ReflectionTestUtils.setField(tenderService, "usersRepo", usersRepo);
        ReflectionTestUtils.setField(tenderService, "accessPolicy", policy);
        ReflectionTestUtils.setField(tenderService, "userScopeService", userScope);
        ReflectionTestUtils.setField(tenderService, "boqRepo", mock(TenderBoqItemRepo.class));
        ReflectionTestUtils.setField(tenderService, "eligibilityRepo", mock(TenderEligibilityRepo.class));
        ReflectionTestUtils.setField(tenderService, "documentRepo", mock(TenderDocumentRepo.class));
        ReflectionTestUtils.setField(tenderService, "docRequestRepo", mock(TenderDocRequestRepo.class));
        ReflectionTestUtils.setField(tenderService, "approvalLogRepo", mock(TenderApprovalLogRepo.class));

        customersService = new CustomersService();
        ReflectionTestUtils.setField(customersService, "customersRepo", customersRepo);
        ReflectionTestUtils.setField(customersService, "tenderRepo", tenderRepo);
        ReflectionTestUtils.setField(customersService, "usersRepo", usersRepo);
        ReflectionTestUtils.setField(customersService, "accessPolicy", policy);
        ReflectionTestUtils.setField(customersService, "userScopeService", userScope);
    }

    // ── helpers ──────────────────────────────────────────────────────────
    TenderWrapper wrapper(String company, String status, Long assignee) {
        TenderWrapper w = new TenderWrapper();
        w.setTenderNumber("T-" + company);
        w.setTenderName("Tender " + company);
        w.setClientCompany(company);
        w.setClientGstin("27AAAAS0000A1Z5");
        w.setClientContactEmail("tenders@example.com");
        w.setClientContactPhone("9999900000");
        w.setStatus(status);
        w.setAssignedTo(assignee);
        return w;
    }

    TenderEntity tenderOf(TenderWrapper w) {
        return tenders.get(w.getId());
    }

    List<CustomersEntity> generated(Long tenderId) {
        List<CustomersEntity> out = new ArrayList<>();
        customers.values().forEach(c -> { if (tenderId.equals(c.getSourceTenderId())) out.add(c); });
        return out;
    }

    CustomersEntity independentClient(String company, Long assignee) {
        CustomersEntity c = new CustomersEntity();
        c.setId(++seq);
        c.setName(company);
        c.setCompanyName(company);
        c.setGstNumber("27AAAAS0000A1Z5");
        c.setEmail("tenders@example.com");
        c.setPhone("9999900000");
        c.setAssignedTo(assignee);
        c.setCreatedBy(assignee);
        customers.put(c.getId(), c);
        return c;
    }

    TenderWrapper wonTender(String company, Long assignee) throws CustomException {
        TenderWrapper created = tenderService.create(wrapper(company, "Draft", assignee), ADMIN, "admin");
        TenderWrapper w = wrapper(company, "Won", assignee);
        tenderService.update(created.getId(), w, ADMIN, ADMIN_ROLE, "admin");
        return created;
    }

    CustomerRequestWrapper assignment(Long to) {
        CustomerRequestWrapper r = new CustomerRequestWrapper();
        r.setAssignedTo(to);
        return r;
    }

    // ── A: tender becomes Won ────────────────────────────────────────────
    @Test
    void wonTenderGeneratesExactlyOneClientAssignedToTheTendersAssignee() throws Exception {
        TenderWrapper t = wonTender("Solar A", A);

        List<CustomersEntity> clients = generated(t.getId());
        assertEquals(1, clients.size());
        assertEquals(A, clients.get(0).getAssignedTo());
        assertEquals(t.getId(), clients.get(0).getSourceTenderId());
    }

    // ── B: reassign from Tenders ─────────────────────────────────────────
    @Test
    void reassigningTheTenderMovesItsClient() throws Exception {
        TenderWrapper t = wonTender("Solar A", A);

        tenderService.assign(t.getId(), B, ADMIN, ADMIN_ROLE);

        assertEquals(B, tenderOf(t).getAssignedTo());
        assertEquals(B, generated(t.getId()).get(0).getAssignedTo());
    }

    @Test
    void savingTheTenderFormWithANewAssigneeAlsoMovesItsClient() throws Exception {
        TenderWrapper t = wonTender("Solar A", A);
        TenderWrapper edit = wrapper("Solar A", "Won", B);

        tenderService.update(t.getId(), edit, ADMIN, ADMIN_ROLE, "admin");

        assertEquals(B, generated(t.getId()).get(0).getAssignedTo());
    }

    // ── C: reassign from Clients ─────────────────────────────────────────
    @Test
    void reassigningTheGeneratedClientMovesItsExactSourceTender() throws Exception {
        TenderWrapper t = wonTender("Solar A", B);
        CustomersEntity client = generated(t.getId()).get(0);

        customersService.updateCustomer(client.getId(), assignment(C), ADMIN, ADMIN_ROLE);

        assertEquals(C, customers.get(client.getId()).getAssignedTo());
        assertEquals(C, tenderOf(t).getAssignedTo());
    }

    // ── unassign (empty Assign To) ───────────────────────────────────────
    @Test
    void clearingTheAssigneeOnTheTenderClearsItsClientToo() throws Exception {
        TenderWrapper t = wonTender("Solar A", A);

        tenderService.assign(t.getId(), null, ADMIN, ADMIN_ROLE);

        assertNull(tenderOf(t).getAssignedTo());
        assertNull(generated(t.getId()).get(0).getAssignedTo());
    }

    @Test
    void saveWithUnassignFlagClearsButABareNullChangesNothing() throws Exception {
        TenderWrapper t = wonTender("Solar A", A);

        tenderService.update(t.getId(), wrapper("Solar A", "Won", null), ADMIN, ADMIN_ROLE, "admin");   // not supplied
        assertEquals(A, tenderOf(t).getAssignedTo());

        TenderWrapper clear = wrapper("Solar A", "Won", null);
        clear.setUnassign(true);
        tenderService.update(t.getId(), clear, ADMIN, ADMIN_ROLE, "admin");
        assertNull(tenderOf(t).getAssignedTo());
        assertNull(generated(t.getId()).get(0).getAssignedTo());
    }

    @Test
    void clearingTheAssigneeOnTheClientClearsItsSourceTender() throws Exception {
        TenderWrapper t = wonTender("Solar A", A);
        CustomersEntity client = generated(t.getId()).get(0);

        CustomerRequestWrapper clear = new CustomerRequestWrapper();
        clear.setUnassign(true);
        customersService.updateCustomer(client.getId(), clear, ADMIN, ADMIN_ROLE);

        assertNull(customers.get(client.getId()).getAssignedTo());
        assertNull(tenderOf(t).getAssignedTo());
    }

    @Test
    void anUpdateThatDoesNotMentionTheAssigneeLeavesItAlone() throws Exception {
        TenderWrapper t = wonTender("Solar A", A);
        CustomersEntity client = generated(t.getId()).get(0);

        customersService.updateCustomer(client.getId(), new CustomerRequestWrapper(), ADMIN, ADMIN_ROLE);

        assertEquals(A, customers.get(client.getId()).getAssignedTo());
        assertEquals(A, tenderOf(t).getAssignedTo());
    }

    // ── D: independent client ────────────────────────────────────────────
    @Test
    void independentClientReassignmentTouchesNoTender() throws Exception {
        TenderWrapper t = wonTender("Solar A", A);               // same GSTIN/email/phone as the client below
        CustomersEntity solo = independentClient("Solar A", A);

        customersService.updateCustomer(solo.getId(), assignment(B), ADMIN, ADMIN_ROLE);

        assertEquals(B, customers.get(solo.getId()).getAssignedTo());
        assertEquals(A, tenderOf(t).getAssignedTo(), "an unlinked client must never move a tender");
        assertNull(solo.getSourceTenderId());
    }

    // ── E: similar details, only the explicit pair is synchronised ───────
    @Test
    void clientsWithMatchingDetailsAreNeverLinkedOrSynchronised() throws Exception {
        CustomersEntity lookalike1 = independentClient("Solar A", A);
        CustomersEntity lookalike2 = independentClient("Solar A", C);
        TenderWrapper t1 = wonTender("Solar A", A);
        TenderWrapper t2 = tenderService.create(wrapper("Solar A", "Draft", A), ADMIN, "admin");

        // winning t1 creates its own client; it does not adopt a look-alike
        assertEquals(1, generated(t1.getId()).size());
        assertNull(lookalike1.getSourceTenderId());
        assertNull(lookalike2.getSourceTenderId());
        assertEquals(0, generated(t2.getId()).size());

        tenderService.assign(t1.getId(), B, ADMIN, ADMIN_ROLE);

        assertEquals(B, generated(t1.getId()).get(0).getAssignedTo());
        assertEquals(A, lookalike1.getAssignedTo());
        assertEquals(C, lookalike2.getAssignedTo());
        assertEquals(A, tenderOf(t2).getAssignedTo());
    }

    // ── F: multiple tenders ──────────────────────────────────────────────
    @Test
    void reassigningOneTenderLeavesTheOtherPairAlone() throws Exception {
        TenderWrapper t1 = wonTender("Solar A", A);
        TenderWrapper t2 = wonTender("Wind B", A);

        tenderService.assign(t1.getId(), B, ADMIN, ADMIN_ROLE);

        assertEquals(B, generated(t1.getId()).get(0).getAssignedTo());
        assertEquals(A, tenderOf(t2).getAssignedTo());
        assertEquals(A, generated(t2.getId()).get(0).getAssignedTo());
    }

    // ── G: repeated Won ──────────────────────────────────────────────────
    @Test
    void repeatedWonAndRetriesNeverCreateASecondClient() throws Exception {
        TenderWrapper t = wonTender("Solar A", A);
        for (int i = 0; i < 3; i++) {
            tenderService.update(t.getId(), wrapper("Solar A", "Lost", A), ADMIN, ADMIN_ROLE, "admin");
            tenderService.update(t.getId(), wrapper("Solar A", "Won", A), ADMIN, ADMIN_ROLE, "admin");
            tenderService.update(t.getId(), wrapper("Solar A", "Won", A), ADMIN, ADMIN_ROLE, "admin"); // retry
        }
        assertEquals(1, generated(t.getId()).size());
    }

    @Test
    void creatingAWonTenderDirectlyGeneratesOneClient() throws Exception {
        TenderWrapper t = tenderService.create(wrapper("Solar A", "Won", A), ADMIN, "admin");
        assertEquals(1, generated(t.getId()).size());
        assertEquals(A, generated(t.getId()).get(0).getAssignedTo());
    }

    // ── H: authorisation ─────────────────────────────────────────────────
    @Test
    void anOutsiderCannotReassignATenderOrItsClient() throws Exception {
        TenderWrapper t = wonTender("Solar A", A);

        assertThrows(CustomException.class, () -> tenderService.assign(t.getId(), OUTSIDER, OUTSIDER, STAFF_ROLE));
        assertThrows(CustomException.class,
                () -> tenderService.update(t.getId(), wrapper("Solar A", "Won", OUTSIDER), OUTSIDER, STAFF_ROLE, "x"));

        assertEquals(A, tenderOf(t).getAssignedTo());
        assertEquals(A, generated(t.getId()).get(0).getAssignedTo());
    }

    @Test
    void cannotAssignToAUserOutsideTheCallersScope() throws Exception {
        TenderWrapper t = wonTender("Solar A", A);   // A is the assignee, so A may see/edit it

        // A (staff) may only assign to themselves; B is out of scope
        CustomException e = assertThrows(CustomException.class, () -> tenderService.assign(t.getId(), B, A, STAFF_ROLE));
        assertTrue(e.getMessage().contains("not allowed to assign"));

        assertEquals(A, tenderOf(t).getAssignedTo());
        assertEquals(A, generated(t.getId()).get(0).getAssignedTo());
    }

    @Test
    void clientReassignIsRefusedWhenTheCallerCannotSeeTheSourceTender() throws Exception {
        TenderWrapper t = wonTender("Solar A", A);
        CustomersEntity client = generated(t.getId()).get(0);
        // OUTSIDER created the client (so may edit it) but has no access to the tender (admin-created, assigned to A)
        client.setCreatedBy(OUTSIDER);

        // assigning to themselves is allowed, so the refusal must come from the tender-access check
        CustomException e = assertThrows(CustomException.class,
                () -> customersService.updateCustomer(client.getId(), assignment(OUTSIDER), OUTSIDER, STAFF_ROLE));
        assertTrue(e.getMessage().contains("source tender"));
        assertEquals(A, tenderOf(t).getAssignedTo());
        assertEquals(A, customers.get(client.getId()).getAssignedTo(), "refusal must leave the client untouched too");
    }

    // ── I: failure handling ──────────────────────────────────────────────
    @Test
    void aFailureWhileUpdatingTheClientPropagatesAndTheMethodsAreTransactional() throws Exception {
        TenderWrapper t = wonTender("Solar A", A);

        failCustomerSave = new IllegalStateException("db down");
        assertThrows(IllegalStateException.class, () -> tenderService.assign(t.getId(), B, ADMIN, ADMIN_ROLE));
        failCustomerSave = null;

        // Rollback is Spring's; what this code guarantees is that every method that writes
        // both records is transactional for checked exceptions too.
        for (String name : List.of("create", "update", "assign")) {
            Method m = java.util.Arrays.stream(TenderService.class.getMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            Transactional tx = m.getAnnotation(Transactional.class);
            assertNotNull(tx, name + " must be @Transactional");
            assertTrue(Set.of(tx.rollbackFor()).contains(Exception.class), name + " must roll back on checked exceptions");
        }
        Method upd = java.util.Arrays.stream(CustomersService.class.getMethods())
                .filter(x -> x.getName().equals("updateCustomer")).findFirst().orElseThrow();
        assertTrue(Set.of(upd.getAnnotation(Transactional.class).rollbackFor()).contains(Exception.class));
    }
}
