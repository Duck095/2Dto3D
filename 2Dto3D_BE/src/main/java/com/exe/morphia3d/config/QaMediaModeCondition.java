package com.exe.morphia3d.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class QaMediaModeCondition implements Condition {
    public enum Expected { NON_QA, QA_DISABLED, QA_LIVE }

    private final Expected expected;

    public QaMediaModeCondition(Expected expected) {
        this.expected = expected;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        QaMediaActivation.Mode mode = QaMediaActivation.resolve(context.getEnvironment()).mode();
        return mode.name().equals(expected.name());
    }

    public static final class NonQa extends QaMediaModeCondition {
        public NonQa() { super(Expected.NON_QA); }
    }

    public static final class QaDisabled extends QaMediaModeCondition {
        public QaDisabled() { super(Expected.QA_DISABLED); }
    }

    public static final class QaLive extends QaMediaModeCondition {
        public QaLive() { super(Expected.QA_LIVE); }
    }
}
