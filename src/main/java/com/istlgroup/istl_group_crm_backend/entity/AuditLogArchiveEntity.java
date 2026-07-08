package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_logs_v2_archive")
public class AuditLogArchiveEntity extends AuditLogBase {
}
