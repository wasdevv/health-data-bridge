package dev.wasdevv.hdb.testsupport;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Disables the whole test class when there is no database, before Spring tries to start. Surefire and
 * Failsafe report a disabled class as skipped. An assumption failing in {@code @BeforeAll} would show
 * up as "Tests run: 0" instead, which hides the gap.
 */
public class RequiresTestDatabase implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return TestDatabase.available()
                ? ConditionEvaluationResult.enabled("database available")
                : ConditionEvaluationResult.disabled("no database: set HDB_TEST_JDBC_URL or run with Docker");
    }
}
