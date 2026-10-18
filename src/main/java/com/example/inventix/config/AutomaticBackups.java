package com.example.inventix.config;

import com.example.inventix.service.BackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Backs the database up without anyone pressing a button: when the newest automatic backup is older than
 * inventix.backup.auto.interval (checked shortly after start and then every hour), and when the backend shuts down.
 * Only the newest inventix.backup.auto.keep automatic backups are kept; backups made with Back up now are never
 * deleted. Pointing inventix.backup.dir at a synced folder (OneDrive, Dropbox...) puts these copies off the machine.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "inventix.backup.auto.enabled", havingValue = "true", matchIfMissing = true)
public class AutomaticBackups {

    private static final Logger log = LoggerFactory.getLogger(AutomaticBackups.class);

    private final BackupService backupService;
    private final Duration interval;
    private final int keep;
    private final boolean onShutdown;
    private final Clock clock;

    @Autowired
    public AutomaticBackups(BackupService backupService,
                            @Value("${inventix.backup.auto.interval:24h}") Duration interval,
                            @Value("${inventix.backup.auto.keep:14}") int keep,
                            @Value("${inventix.backup.auto.on-shutdown:true}") boolean onShutdown) {
        this(backupService, interval, keep, onShutdown, Clock.systemDefaultZone());
    }

    AutomaticBackups(BackupService backupService, Duration interval, int keep, boolean onShutdown, Clock clock) {
        this.backupService = backupService;
        this.interval = interval;
        this.keep = keep;
        this.onShutdown = onShutdown;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${inventix.backup.auto.first-check:1m}", fixedDelayString = "1h")
    public void backUpIfDue() {
        Optional<Instant> latest = backupService.latestAutomaticBackup();
        if (latest.isPresent() && latest.get().plus(interval).isAfter(clock.instant())) {
            return;
        }
        backUp(latest.isEmpty() ? "no automatic backup yet" : "the last one is older than " + interval);
    }

    @EventListener(ContextClosedEvent.class)
    public void backUpOnShutdown() {
        if (onShutdown) {
            backUp("the backend is shutting down");
        }
    }

    // Never throws: a failed backup is logged and tried again at the next check, it doesn't stop the app.
    void backUp(String reason) {
        try {
            var backup = backupService.createAutomaticBackup();
            log.info("Automatic backup ({}): {}", reason, backup.path());
            backupService.pruneAutomaticBackups(keep);
        } catch (RuntimeException e) {
            log.warn("Automatic backup ({}) failed: {}", reason, e.getMessage());
        }
    }
}
