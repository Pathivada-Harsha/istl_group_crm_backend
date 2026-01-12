package com.istlgroup.istl_group_crm_backend.wrapperClasses;


import com.istlgroup.istl_group_crm_backend.wrapperClasses.QuotationDTO;
import com.istlgroup.istl_group_crm_backend.entity.QuotationEntity;
import com.istlgroup.istl_group_crm_backend.entity.QuotationItemEntity;

import java.util.stream.Collectors;

/**
 * Mapper to convert Quotation entities to DTOs
 * Prevents circular reference issues in JSON serialization
 */
public class QuotationMapper {
    
    /**
     * Convert QuotationEntity to QuotationDTO
     */
    public static QuotationDTO toDTO(QuotationEntity entity) {
        if (entity == null) {
            return null;
        }
        
        QuotationDTO dto = QuotationDTO.builder()
                .id(entity.getId())
                .quoteNo(entity.getQuoteNo())
                .leadId(entity.getLeadId())
                .proposalId(entity.getProposalId())
                .customerId(entity.getCustomerId())
                .vendorId(entity.getVendorId())
                .rfqId(entity.getRfqId())
                .type(entity.getType())
                .status(entity.getStatus())
                .validTill(entity.getValidTill())
                .totalValue(entity.getTotalValue())
                .preparedBy(entity.getPreparedBy())
                .groupName(entity.getGroupName())
                .subGroupName(entity.getSubGroupName())
                .projectId(entity.getProjectId())
                .vendorContact(entity.getVendorContact())
                .vendorRating(entity.getVendorRating())
                .deliveryTime(entity.getDeliveryTime())
                .paymentTerms(entity.getPaymentTerms())
                .warranty(entity.getWarranty())
                .notes(entity.getNotes())
                .category(entity.getCategory())
                .uploadedAt(entity.getUploadedAt())
                .fileName(entity.getFileName())
                .fileType(entity.getFileType())
                .fileSize(entity.getFileSize())
                .poId(entity.getPoId())
                .build();
        
        // Convert items if present
        if (entity.getItems() != null && !entity.getItems().isEmpty()) {
            dto.setItems(
                entity.getItems().stream()
                    .map(QuotationMapper::toItemDTO)
                    .collect(Collectors.toList())
            );
        }
        
        return dto;
    }
    
    /**
     * Convert QuotationItemEntity to QuotationItemDTO
     */
    public static QuotationDTO.QuotationItemDTO toItemDTO(QuotationItemEntity entity) {
        if (entity == null) {
            return null;
        }
        
        return QuotationDTO.QuotationItemDTO.builder()
                .id(entity.getId())
                .lineNo(entity.getLineNo())
                .itemName(entity.getItemName())
                .description(entity.getDescription())
                .quantity(entity.getQuantity())
                .unitPrice(entity.getUnitPrice())
                .taxPercent(entity.getTaxPercent())
                .lineTotal(entity.getLineTotal())
                .deliveryLeadTime(entity.getDeliveryLeadTime())
                .build();
    }
}

