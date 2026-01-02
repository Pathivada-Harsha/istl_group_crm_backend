package com.istlgroup.istl_group_crm_backend.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "menu_permissions")
@Data
public class MenuPermissionsEntity {
	
	    @Id
	    @GeneratedValue(strategy = GenerationType.IDENTITY)
	    private Long id;
	    @Column(name = "users_id")
	    private Long usersId;

	    private Integer dashboard;	    
		private Integer analytics;
	    private Integer documents;
	    private Integer settings;
	    private Integer follow_ups;
	    private Integer reports;
	    private Integer invoices;
	    private Integer sales_clients;
	    private Integer sales_leads;
	    private Integer sales_estimation;
	    private Integer procurement_venders;
	    private Integer procurement_quotations_recived;
	    private Integer procurement_purchase_orders;
	    private Integer procurement_bills_received;
	    private Integer office_use;
}
