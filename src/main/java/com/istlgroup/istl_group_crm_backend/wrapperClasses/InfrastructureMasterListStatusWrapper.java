package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InfrastructureMasterListStatusWrapper {
    private String currentVersionLabel;
    private String notificationNumber;
    private LocalDate notificationDate;
    private String sourcePageUrl;
    private String pdfUrl;
    private String documentHash;
    private String fetchedAt;
    private String lastCheckAt;
    private String lastSyncStatus;
    private String lastError;
}
