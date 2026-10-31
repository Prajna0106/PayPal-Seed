package com.paypalseed.platform.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Runs Flyway once per module: each module owns {@code db/migration/<module>}
 * and the schema of the same name (hyphens dropped), with its own history table. Modules are
 * discovered from the classpath, so adding a module needs no configuration here.
 */
public final class ModuleMigrations {

    private static final Logger log = LoggerFactory.getLogger(ModuleMigrations.class);
    private static final Pattern MODULE_DIR = Pattern.compile("db/migration/([a-z][a-z0-9-]*)/");

    private final DataSource dataSource;

    public ModuleMigrations(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void migrate() {
        for (String module : discoverModules()) {
            String schema = schemaOf(module);
            var result = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .locations("classpath:db/migration/" + module)
                    .failOnMissingLocations(true)
                    .load()
                    .migrate();
            log.info("Migrated schema {} ({} migrations applied)", schema, result.migrationsExecuted);
        }
    }

    static String schemaOf(String module) {
        return module.replace("-", "");
    }

    static Set<String> discoverModules() {
        try {
            Set<String> modules = new TreeSet<>();
            for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*/V*.sql")) {
                Matcher m = MODULE_DIR.matcher(r.getURL().toString().replace('\\', '/'));
                if (m.find()) {
                    modules.add(m.group(1));
                }
            }
            return modules;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not scan module migrations", e);
        }
    }
}
