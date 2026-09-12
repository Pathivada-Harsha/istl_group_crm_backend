package com.istlgroup.istl_group_crm_backend.service.infrastructure;

import java.util.List;

public record ParsedCategory(String name, int displayOrder, List<ParsedSubCategory> subCategories) {
}
