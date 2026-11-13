package dev.invoiceintel.app.reconcile;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code invoice-intel.order-history.*} (architecture.md §6.7, §10).
 *
 * @param mappingFile the column mapping of the order-history export ({@code
 *     config/order-history-mapping.yml}); read on every import, so edits apply without a restart
 */
@ConfigurationProperties("invoice-intel.order-history")
public record OrderHistoryProperties(Path mappingFile) {}
