package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code findByEmailIgnoreCase} against the real schema.
 *
 * <p>The lead import resolves its "Assigned To (Email)" column through this method, and a
 * spreadsheet filled in by hand will not match the stored casing. Spring derives the query
 * from the method name, so nothing before runtime proves it produces the case-insensitive
 * SQL the import depends on — a unit test with a mocked repository would assert the mock,
 * not the database.
 *
 * <p>Written to pass on any dataset, including an empty one: it discovers a real user
 * rather than assuming a fixture exists.
 */
@SpringBootTest
class UsersRepoEmailLookupTest {

    @Autowired UsersRepo usersRepo;

    /** Any user that actually has an email, or null when the table has none. */
    private UsersEntity anyUserWithEmail() {
        return usersRepo.findAll().stream()
                .filter(u -> u.getEmail() != null && !u.getEmail().isBlank())
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("an email matches whatever case the spreadsheet used")
    void lookupIsCaseInsensitive() {
        UsersEntity known = anyUserWithEmail();
        if (known == null) return;                 // empty database — nothing to assert
        String email = known.getEmail();

        List<UsersEntity> exact = usersRepo.findByEmailIgnoreCase(email);
        List<UsersEntity> upper = usersRepo.findByEmailIgnoreCase(email.toUpperCase());
        List<UsersEntity> lower = usersRepo.findByEmailIgnoreCase(email.toLowerCase());

        assertThat(exact).as("the stored address must resolve").isNotEmpty();
        assertThat(upper).as("UPPERCASE in the sheet must resolve to the same user")
                .extracting(UsersEntity::getId).containsExactlyElementsOf(
                        exact.stream().map(UsersEntity::getId).toList());
        assertThat(lower).as("lowercase in the sheet must resolve to the same user")
                .extracting(UsersEntity::getId).containsExactlyElementsOf(
                        exact.stream().map(UsersEntity::getId).toList());
    }

    @Test
    @DisplayName("an unknown address returns empty rather than throwing")
    void unknownEmailReturnsEmpty() {
        assertThat(usersRepo.findByEmailIgnoreCase("definitely-not-a-user@nowhere.invalid"))
                .as("the import turns this into a rejected row, so it must not throw")
                .isEmpty();
    }
}
