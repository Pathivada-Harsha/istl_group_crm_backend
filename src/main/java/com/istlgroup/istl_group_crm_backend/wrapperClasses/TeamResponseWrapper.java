package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

@Data
public class TeamResponseWrapper {

    private Long   id;
    private String name;
    private String description;
    private Long   createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Full member objects — used for display in the table
    private List<TeamMemberSummary> members;
    private int memberCount;

    // Plain ID list — used by the frontend modal to pre-check checkboxes
    private List<Long> memberIds;

    @Data
    public static class TeamMemberSummary {
        private Long   id;
        private String name;
        private String role;
    }
}