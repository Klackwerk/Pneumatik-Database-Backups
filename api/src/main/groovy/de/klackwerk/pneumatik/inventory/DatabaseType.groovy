package de.klackwerk.pneumatik.inventory

enum DatabaseType {
    MYSQL('MySQL / MariaDB'),
    POSTGRESQL('PostgreSQL')

    final String displayName

    private DatabaseType(String displayName) {
        this.displayName = displayName
    }
}
