package com.flexforge.app;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.ConfigurationLoader;
import com.puppycrawl.tools.checkstyle.PropertiesExpander;
import com.puppycrawl.tools.checkstyle.api.AuditEvent;
import com.puppycrawl.tools.checkstyle.api.AuditListener;
import com.puppycrawl.tools.checkstyle.api.Configuration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-GOV-06 后端半边：故意违规 fixture 必须被 Checkstyle 拦截并命中预期规则，
 * 否则说明门禁已被放宽（fail-open，docs/11 §3）。违规样例不参与正常源码构建。
 */
class CheckstyleFixtureTest {

    @Test
    void overLimitFixtureMustFailWithExpectedRules() throws Exception {
        CheckResult result = runFixtureCheck();

        assertThat(result.errorCount()).isGreaterThan(0);
        assertThat(result.sources()).anyMatch(source -> source.contains("FileLengthCheck"));
        assertThat(result.sources()).anyMatch(source -> source.contains("MethodLengthCheck"));
    }

    private CheckResult runFixtureCheck() throws Exception {
        List<String> sources = new ArrayList<>();
        Checker checker = new Checker();
        checker.setModuleClassLoader(CheckstyleFixtureTest.class.getClassLoader());
        checker.configure(loadConfiguration());
        checker.addListener(collecting(sources::add));
        try {
            return new CheckResult(checker.process(List.of(fixtureFile())), sources);
        } finally {
            checker.destroy();
        }
    }

    private Configuration loadConfiguration() throws Exception {
        return ConfigurationLoader.loadConfiguration(
                configFile().getAbsolutePath(), new PropertiesExpander(new Properties()));
    }

    private static AuditListener collecting(Consumer<String> onError) {
        return new AuditListener() {
            @Override
            public void auditStarted(AuditEvent event) {
            }

            @Override
            public void auditFinished(AuditEvent event) {
            }

            @Override
            public void fileStarted(AuditEvent event) {
            }

            @Override
            public void fileFinished(AuditEvent event) {
            }

            @Override
            public void addError(AuditEvent event) {
                onError.accept(event.getSourceName());
            }

            @Override
            public void addException(AuditEvent event, Throwable throwable) {
            }
        };
    }

    private static File fixtureFile() {
        return rootPath().resolve("tests/fixtures/violations/BackendOverLimit.java").toFile();
    }

    private static File configFile() {
        return rootPath().resolve("backend/config/checkstyle.xml").toFile();
    }

    private static Path rootPath() {
        return Path.of("..", "..").toAbsolutePath().normalize();
    }

    private record CheckResult(int errorCount, List<String> sources) {
    }
}
