package com.paypalseed.tools.jooq;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.ForcedType;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Target;

/**
 * Generates the jOOQ classes of one module schema. The module's own Flyway migrations run against a
 * throwaway PostgreSQL 16 and jOOQ reads the result, so the generated code can never drift from the
 * migrations and the build needs neither Docker nor a running database.
 *
 * <p>Arguments: schema, migrations directory, output directory, target package.
 */
public final class GenerateJooq {

    private static final String SUPERUSER = "postgres";

    private GenerateJooq() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: GenerateJooq <schema> <migrations dir> <output dir> <package>");
        }
        String schema = args[0];
        Path migrations = Path.of(args[1]);
        Path output = Path.of(args[2]);
        String packageName = args[3];

        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            Flyway.configure()
                    .dataSource(postgres.getPostgresDatabase())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .locations("filesystem:" + migrations.toAbsolutePath())
                    .load()
                    .migrate();
            GenerationTool.generate(configuration(postgres.getJdbcUrl(SUPERUSER, SUPERUSER), schema, output, packageName));
        }
    }

    private static Configuration configuration(String jdbcUrl, String schema, Path output, String packageName) {
        return new Configuration()
                .withJdbc(new Jdbc()
                        .withDriver("org.postgresql.Driver")
                        .withUrl(jdbcUrl)
                        .withUser(SUPERUSER)
                        .withPassword(SUPERUSER))
                .withGenerator(new Generator()
                        .withDatabase(new Database()
                                .withName("org.jooq.meta.postgres.PostgresDatabase")
                                .withInputSchema(schema)
                                .withExcludes("flyway_schema_history")
                                // timestamptz is an instant, and the schema's smallint columns are small counters that
                                // the domain models as int.
                                .withForcedTypes(
                                        new ForcedType().withName("INSTANT").withIncludeTypes("(?i:TIMESTAMPTZ|TIMESTAMP\s+WITH\s+TIME\s+ZONE)"),
                                        new ForcedType().withName("INTEGER").withIncludeTypes("(?i:INT2|SMALLINT)")))
                        .withGenerate(new Generate()
                                .withRecords(true)
                                .withPojos(false)
                                .withDaos(false)
                                .withInterfaces(false)
                                .withIndexes(false)
                                .withDeprecated(false)
                                .withFluentSetters(true)
                                .withGeneratedAnnotation(false))
                        .withTarget(new Target()
                                .withPackageName(packageName)
                                .withDirectory(output.toAbsolutePath().toString())
                                .withClean(true)));
    }
}
