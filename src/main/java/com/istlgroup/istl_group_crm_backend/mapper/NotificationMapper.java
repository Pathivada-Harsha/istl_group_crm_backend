package com.istlgroup.istl_group_crm_backend.mapper;

import com.istlgroup.istl_group_crm_backend.dto.NotificationRequestDTO;
import com.istlgroup.istl_group_crm_backend.dto.NotificationResponseDTO;
import com.istlgroup.istl_group_crm_backend.entity.NotificationEntity;
import org.springframework.stereotype.Component;

@Component
public class NotificationMapper {

    public NotificationEntity toEntity(NotificationRequestDTO dto) {
        NotificationEntity e = new NotificationEntity();
        e.setUserId(dto.getUserId());
        e.setTitle(dto.getTitle());
        e.setMessage(dto.getMessage());
        e.setModule(dto.getModule());
        e.setReferenceId(dto.getReferenceId());
        e.setNotificationType(dto.getNotificationType());
        e.setIsRead(Boolean.FALSE);
        return e;
    }

    public NotificationResponseDTO toResponse(NotificationEntity e) {
        NotificationResponseDTO dto = new NotificationResponseDTO();
        dto.setId(e.getId());
        dto.setUserId(e.getUserId());
        dto.setTitle(e.getTitle());
        dto.setMessage(e.getMessage());
        dto.setModule(e.getModule());
        dto.setReferenceId(e.getReferenceId());
        dto.setNotificationType(e.getNotificationType());
        dto.setIsRead(e.getIsRead());
        dto.setCreatedAt(e.getCreatedAt());
        return dto;
    }
}
