package com.istlgroup.istl_group_crm_backend.service.infrastructure;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a freshly parsed Harmonized Master List before it is allowed to
 * become the current version. A parsed list is only ever activated after
 * this passes — otherwise the currently active version is left untouched
 * (see {@link InfrastructureMasterListSyncService}).
 */
@Component
public class InfrastructureMasterListValidator {

    /**
     * Real category names in the actual Gazette notices top out around 40
     * characters ("Social and Commercial Infrastructure"). A run of table
     * end-of-page noise or a stray footnote paragraph mis-attributed as a
     * category by the parser reads as a long sentence instead — this catches
     * that class of corruption without knowing the government's exact wording.
     */
    private static final int MAX_CATEGORY_NAME_LENGTH = 80;

    public record ValidationResult(boolean valid, List<String> reasons) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }
    }

    public ValidationResult validate(List<ParsedCategory> categories) {
        List<String> reasons = new ArrayList<>();

        if (categories == null || categories.isEmpty()) {
            reasons.add("No categories were extracted from the document.");
            return new ValidationResult(false, reasons);
        }

        Set<String> categoryNames = new HashSet<>();
        for (ParsedCategory category : categories) {
            if (category.name() == null || category.name().isBlank()) {
                reasons.add("A category with a blank name was extracted.");
                continue;
            }
            if (category.name().length() > MAX_CATEGORY_NAME_LENGTH) {
                reasons.add("Category name looks like mis-parsed body text, not a real category (too long): '"
                        + category.name().substring(0, MAX_CATEGORY_NAME_LENGTH) + "...'");
            }
            String key = category.name().trim().toLowerCase();
            if (!categoryNames.add(key)) {
                reasons.add("Duplicate category name: '" + category.name() + "'.");
            }
            if (category.subCategories() == null || category.subCategories().isEmpty()) {
                reasons.add("Category '" + category.name() + "' has no sub-categories.");
            } else {
                Set<String> subNames = new HashSet<>();
                for (ParsedSubCategory sub : category.subCategories()) {
                    if (sub.name() == null || sub.name().isBlank()) {
                        reasons.add("Category '" + category.name() + "' has a sub-category with a blank name.");
                        continue;
                    }
                    String subKey = sub.name().trim().toLowerCase();
                    if (!subNames.add(subKey)) {
                        reasons.add("Duplicate sub-category '" + sub.name()
                                + "' within category '" + category.name() + "'.");
                    }
                    if (sub.displayOrder() < 1) {
                        reasons.add("Sub-category '" + sub.name() + "' has an invalid display order.");
                    }
                }
            }
            if (category.displayOrder() < 1) {
                reasons.add("Category '" + category.name() + "' has an invalid display order.");
            }
        }

        return reasons.isEmpty() ? ValidationResult.ok() : new ValidationResult(false, reasons);
    }
}
