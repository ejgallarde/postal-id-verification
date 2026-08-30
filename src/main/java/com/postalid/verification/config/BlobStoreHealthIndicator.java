package com.postalid.verification.config;

import com.postalid.verification.storage.BlobStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reports whether the blob store is actually usable.
 *
 * <p>Without this the service fails silently in a way that is worse than being down: verification
 * keeps returning VALID and handing out image references, and every one of those references then
 * 404s because the photographs are not reachable. An operator sees a valid card with a broken
 * picture and has no way to tell whether the record is wrong or the storage is.
 *
 * <p>It is registered under the readiness group, so an instance whose storage has gone away is
 * taken out of the load balancer rather than left serving half-answers.
 */
// Bean name blobStoreHealthIndicator yields the health key "blobStore"; naming the bean
// explicitly would collide with the BlobStore bean itself.
@Component
public class BlobStoreHealthIndicator implements HealthIndicator {

    private final BlobStore blobStore;

    public BlobStoreHealthIndicator(BlobStore blobStore) {
        this.blobStore = blobStore;
    }

    @Override
    public Health health() {
        Path root = blobStore.getRoot();

        if (!Files.exists(root)) {
            return Health.down()
                    .withDetail("reason", "storage root does not exist")
                    .withDetail("path", root.toString())
                    .build();
        }
        if (!Files.isDirectory(root)) {
            return Health.down()
                    .withDetail("reason", "storage root is not a directory")
                    .withDetail("path", root.toString())
                    .build();
        }
        if (!Files.isReadable(root)) {
            return Health.down()
                    .withDetail("reason", "storage root is not readable")
                    .withDetail("path", root.toString())
                    .build();
        }

        // Writability matters only to ingest, so a read-only mount is degraded rather than down:
        // the service can still serve every verification, it just cannot load a new batch.
        return Health.up()
                .withDetail("path", root.toString())
                .withDetail("writable", Files.isWritable(root))
                .build();
    }
}
