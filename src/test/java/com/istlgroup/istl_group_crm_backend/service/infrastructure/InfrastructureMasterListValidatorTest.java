package com.istlgroup.istl_group_crm_backend.service.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfrastructureMasterListValidatorTest {

    private final InfrastructureMasterListValidator validator = new InfrastructureMasterListValidator();

    @Test
    void rejectsEmptyList() {
        InfrastructureMasterListValidator.ValidationResult result = validator.validate(List.of());
        assertFalse(result.valid());
        assertTrue(result.reasons().get(0).contains("No categories"));
    }

    @Test
    void rejectsCategoryWithNoSubCategories() {
        ParsedCategory category = new ParsedCategory("Energy", 1, List.of());
        InfrastructureMasterListValidator.ValidationResult result = validator.validate(List.of(category));
        assertFalse(result.valid());
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("no sub-categories")));
    }

    @Test
    void rejectsDuplicateCategoryNames() {
        ParsedCategory a = new ParsedCategory("Energy", 1, List.of(new ParsedSubCategory("Electricity Generation", 1)));
        ParsedCategory b = new ParsedCategory("Energy", 2, List.of(new ParsedSubCategory("Electricity Transmission", 1)));
        InfrastructureMasterListValidator.ValidationResult result = validator.validate(List.of(a, b));
        assertFalse(result.valid());
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("Duplicate category")));
    }

    @Test
    void rejectsDuplicateSubCategoryWithinCategory() {
        ParsedCategory category = new ParsedCategory("Energy", 1, List.of(
                new ParsedSubCategory("Electricity Generation", 1),
                new ParsedSubCategory("Electricity Generation", 2)));
        InfrastructureMasterListValidator.ValidationResult result = validator.validate(List.of(category));
        assertFalse(result.valid());
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("Duplicate sub-category")));
    }

    @Test
    void rejectsBlankNames() {
        ParsedCategory category = new ParsedCategory("  ", 1, List.of(new ParsedSubCategory("X", 1)));
        InfrastructureMasterListValidator.ValidationResult result = validator.validate(List.of(category));
        assertFalse(result.valid());
    }

    @Test
    void rejectsCategoryNameThatLooksLikeMisparsedBodyText() {
        String longSentence = "Shipyard is defined as a floating or land-based facility with the essential "
                + "features of waterfront, turning basin, berthing and docking facility";
        ParsedCategory category = new ParsedCategory(longSentence, 1, List.of(new ParsedSubCategory("X", 1)));
        InfrastructureMasterListValidator.ValidationResult result = validator.validate(List.of(category));
        assertFalse(result.valid());
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("too long")));
    }

    @Test
    void acceptsARealisticValidList() {
        ParsedCategory transport = new ParsedCategory("Transport and Logistics", 1, List.of(
                new ParsedSubCategory("Roads and bridges", 1),
                new ParsedSubCategory("Ports", 2)));
        ParsedCategory energy = new ParsedCategory("Energy", 2, List.of(
                new ParsedSubCategory("Electricity Generation", 1)));
        InfrastructureMasterListValidator.ValidationResult result = validator.validate(List.of(transport, energy));
        assertTrue(result.valid(), String.join("; ", result.reasons()));
    }
}
