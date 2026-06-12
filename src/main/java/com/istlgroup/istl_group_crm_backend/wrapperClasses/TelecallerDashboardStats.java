package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

@Data
public class TelecallerDashboardStats {
    private long total;
    private long pending;
    private long interested;
    private long notInterested;
    private long notResponded;
    private long keepInView;
    /** NOT_RESPONDED leads whose 7-day window has passed — ready to re-contact */
    private long resurfacedToday;
}