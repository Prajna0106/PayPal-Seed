package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.support.IntegrationTest;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Keeps the OpenAPI files honest: every HTTP route of the application is in a file under {@code contracts/openapi},
 * and every path in those files is a route of the application. Path variable names do not matter, only the shape
 * ({@code /loans/{loanId}} and {@code /loans/{id}} are the same route).
 */
class ContractCoverageIT extends IntegrationTest {

    private static final Set<String> PREFIXES = Set.of("/api/", "/internal/", "/agent/");
    private static final Set<String> FILES = Set.of("seed-api.yaml", "internal-jobs.yaml", "agent-tools.yaml");
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    /** Routes that exist in the contract but not in this test's context. One reason each. */
    private static final Map<String, String> CONTRACT_ONLY = Map.of(
        "POST /internal/demo-clock/advance", "registered only when seed.clock.mode=database (demo profile)");

    /** Routes in the application that the contract does not describe. One reason each. */
    private static final Map<String, String> CODE_ONLY = Map.of();

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyRouteIsInTheContractAndEveryContractPathIsARoute() throws IOException {
        Set<String> code = routesOfTheApplication();
        Set<String> contract = routesOfTheContract();

        Set<String> missingFromContract = new TreeSet<>(code);
        missingFromContract.removeAll(contract);
        missingFromContract.removeAll(CODE_ONLY.keySet());

        Set<String> missingFromCode = new TreeSet<>(contract);
        missingFromCode.removeAll(code);
        missingFromCode.removeAll(CONTRACT_ONLY.keySet());

        assertThat(missingFromContract).as("routes with no entry in contracts/openapi").isEmpty();
        assertThat(missingFromCode).as("contract paths with no controller").isEmpty();
        assertThat(code).as("the application exposes routes at all").hasSizeGreaterThan(50);
    }

    @Test
    void allowListEntriesAreStillNeeded() throws IOException {
        Set<String> code = routesOfTheApplication();
        Set<String> contract = routesOfTheContract();
        for (String route : CONTRACT_ONLY.keySet()) {
            assertThat(contract).as("%s is in the contract", route).contains(route);
            assertThat(code).as("%s is not a route here, so it needs its allow-list entry", route).doesNotContain(route);
        }
        for (String route : CODE_ONLY.keySet()) {
            assertThat(code).as("%s is a route", route).contains(route);
            assertThat(contract).as("%s is not in the contract", route).doesNotContain(route);
        }
    }

    private Set<String> routesOfTheApplication() {
        Set<String> routes = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<String> patterns = info.getPathPatternsCondition() == null
                ? Set.of() : info.getPathPatternsCondition().getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (PREFIXES.stream().noneMatch(pattern::startsWith)) {
                    continue;
                }
                assertThat(methods).as("%s must name its HTTP methods", pattern).isNotEmpty();
                for (RequestMethod method : methods) {
                    routes.add(method.name() + " " + shape(pattern));
                }
            }
        }
        return routes;
    }

    private Set<String> routesOfTheContract() throws IOException {
        Path dir = contractsDirectory();
        Set<String> routes = new TreeSet<>();
        for (String file : FILES) {
            try (Reader reader = Files.newBufferedReader(dir.resolve(file))) {
                Object root = new Yaml().load(reader);
                if (!(root instanceof Map<?, ?> document) || !(document.get("paths") instanceof Map<?, ?> paths)) {
                    throw new IllegalStateException(file + " has no paths");
                }
                for (Map.Entry<?, ?> path : paths.entrySet()) {
                    if (!(path.getValue() instanceof Map<?, ?> operations)) {
                        continue;
                    }
                    for (Object method : operations.keySet()) {
                        if (METHODS.contains(String.valueOf(method))) {
                            routes.add(String.valueOf(method).toUpperCase(java.util.Locale.ROOT) + " " + shape(String.valueOf(path.getKey())));
                        }
                    }
                }
            }
        }
        return routes;
    }

    private static String shape(String path) {
        return path.replaceAll("\\{[^}/]*}", "{}");
    }

    private static Path contractsDirectory() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contracts").resolve("openapi");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("contracts/openapi not found above " + Path.of("").toAbsolutePath());
    }
}
