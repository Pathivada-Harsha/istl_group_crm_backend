package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name="page_permissions")
@Data
public class PagePermissionsEntity {

	@Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long user_id;

    // USERS
    private Integer users_view=0;
    private Integer users_create=0;
    private Integer users_edit=0;
    private Integer users_delete=0;

    // ROLES
    private Integer roles_manage=0;

    // CUSTOMERS
    private Integer customers_view=0;
    private Integer customers_create=0;
    private Integer customers_edit=0;
    private Integer customers_delete=0;

    // VENDORS
    private Integer vendors_view=0;
    private Integer vendors_create=0;
    private Integer vendors_edit=0;
    private Integer vendors_delete=0;

    // LEADS
    private Integer leads_view=0;
    private Integer leads_create=0;
    private Integer leads_edit=0;
    private Integer leads_delete=0;
    private Integer leads_assign=0;

    // PROPOSALS
    private Integer proposals_view=0;
    private Integer proposals_create=0;
    private Integer proposals_edit=0;
    private Integer proposals_delete=0;
    private Integer proposals_approve=0;

    // QUOTATIONS SALES
    private Integer quotations_sales_view=0;
    private Integer quotations_sales_create=0;
    private Integer quotations_sales_edit=0;
    private Integer quotations_sales_delete=0;
    private Integer quotations_sales_approve=0;

    // SALES ORDERS
    private Integer sales_orders_view=0;
    private Integer sales_orders_create=0;
    private Integer sales_orders_edit=0;
    private Integer sales_orders_delete=0;
    private Integer sales_orders_approve=0;

    // INVOICES
    private Integer invoices_view=0;
    private Integer invoices_create=0;
    private Integer invoices_edit=0;
    private Integer invoices_delete=0;
    private Integer invoices_send=0;

    // QUOTATIONS PROCUREMENT
    private Integer quotations_procurement_view=0;
    private Integer quotations_procurement_create=0;
    private Integer quotations_procurement_edit=0;
    private Integer quotations_procurement_delete=0;
    private Integer quotations_procurement_approve=0;

    // PURCHASE ORDERS
    private Integer purchase_orders_view=0;
    private Integer purchase_orders_create=0;
    private Integer purchase_orders_edit=0;
    private Integer purchase_orders_delete=0;
    private Integer purchase_orders_approve=0;

    // BILLS
    private Integer bills_view=0;
    private Integer bills_create=0;
    private Integer bills_edit=0;
    private Integer bills_delete=0;
    private Integer bills_approve=0;

    // PAYMENTS
    private Integer payments_view=0;
    private Integer payments_record=0;
    private Integer payments_approve=0;

    // REPORTS
    private Integer reports_sales=0;
    private Integer reports_procurement=0;
    private Integer reports_financial=0;
    private Integer reports_analytics=0;

    // FOLLOWUPS
    private Integer followups_view=0;
    private Integer followups_create=0;
    private Integer followups_edit=0;
    private Integer followups_delete=0;

    // SETTINGS
    private Integer settings_view=0;
    private Integer settings_edit=0;

    // ACTIVITY LOGS
    private Integer activity_logs_view=0;

    // ATTACHMENTS
    private Integer attachments_upload=0;
    private Integer attachments_delete=0;

    // AUDIT
  
    private LocalDateTime created_at;

    private LocalDateTime updated_at;
}
