package com.andre.virtualcard;

import com.andre.virtualcard.support.AbstractPostgreSQLIntegrationTest;
import org.junit.jupiter.api.Test;

class VirtualCardIssuancePlatformApplicationTests extends AbstractPostgreSQLIntegrationTest {

    @Test
    void contextStartsAgainstTestcontainersPostgreSQLWithFlywayMigrationAndHibernateValidation() {
        // Context startup is the assertion: Flyway migrated the container schema and
        // Hibernate ddl-auto=validate accepted the resulting mappings.
    }
}
