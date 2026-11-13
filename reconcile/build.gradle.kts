plugins {
    id("invoiceintel.java-conventions")
}

dependencies {
    api(project(":common"))
    // OrderHistoryImporter writes order_history_item rows in one transaction (REC-1,
    // architecture.md §6.7, §7); app supplies the JdbcClient and transaction manager.
    api(libs.spring.jdbc)
}
