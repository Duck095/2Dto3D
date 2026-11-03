package com.exe.morphia3d.repository;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationCheckConstraintCanonicalizerTest {
    @Test
    void canonicalizesOnlyTheTwoVerifiedPostgresCastRenderingsForAllThirteenMigrationChecks() {
        List<MigrationCheckConstraintCanonicalizer.ConstraintDescriptor> source = new ArrayList<>();
        List<MigrationCheckConstraintCanonicalizer.ConstraintDescriptor> restored = new ArrayList<>();
        for (MigrationCheckConstraintCanonicalizer.ExpectedCheck expected :
                MigrationCheckConstraintCanonicalizer.expectedChecks()) {
            source.add(validDescriptor(expected, sourceRendering(expected)));
            restored.add(validDescriptor(expected, restoredRendering(expected)));
        }
        source.add(auditStatusDescriptor(auditStatusSourceRendering()));
        restored.add(auditStatusDescriptor(auditStatusRestoredRendering()));

        List<String> canonicalSource = MigrationCheckConstraintCanonicalizer.canonicalizeAll(source);
        List<String> canonicalRestore = MigrationCheckConstraintCanonicalizer.canonicalizeAll(restored);

        assertThat(MigrationCheckConstraintCanonicalizer.expectedChecks()).hasSize(13);
        assertThat(canonicalSource).hasSize(14);
        assertThat(canonicalSource).isEqualTo(canonicalRestore);
        for (MigrationCheckConstraintCanonicalizer.ExpectedCheck expected :
                MigrationCheckConstraintCanonicalizer.expectedChecks()) {
            assertThat(canonicalSource).anySatisfy(signature -> {
                assertThat(signature).contains("|" + expected.table() + "|" + expected.name() + "|c|");
                assertThat(signature).contains("column=" + expected.column());
                assertThat(signature).contains("values=" + expected.allowedValues());
                assertThat(signature).contains("operator=IN", "nullSemantics=UNKNOWN_PASSES",
                        "validated=true", "local=true", "inheritanceCount=0", "noInherit=false", "hasParent=false");
            });
        }
    }

    @Test
    void rejectsMissingOrAdditionalAllowedValues() {
        MigrationCheckConstraintCanonicalizer.ExpectedCheck expected = expected("ck_users_role");
        String missing = sourceRendering(expected).replace(", 'DESIGNER'::character varying", "");
        String additional = sourceRendering(expected).replace("]::text[]", ", 'ROOT'::character varying]::text[]");

        assertRejected(expected, missing);
        assertRejected(expected, additional);
    }

    @Test
    void rejectsChangedColumnOperatorPredicateNullBehaviorAndNotExpression() {
        MigrationCheckConstraintCanonicalizer.ExpectedCheck expected = expected("ck_users_role");
        String valid = sourceRendering(expected);

        assertRejected(expected, valid.replace(expected.column() + "::text", "other_column::text"));
        assertRejected(expected, valid.replace(" = ANY ", " <> ANY "));
        assertRejected(expected, valid.replace(" = ANY ", " = ALL "));
        assertRejected(expected, valid.replace("CHECK (", "CHECK (NOT "));
        assertRejected(expected, valid.replace("CHECK (", "CHECK (" + expected.column()
                + " IS NOT NULL AND "));
    }

    @Test
    void rejectsChangedConstraintTypeValidationAndInheritanceMetadata() {
        MigrationCheckConstraintCanonicalizer.ExpectedCheck expected = expected("ck_users_role");
        String definition = sourceRendering(expected);
        String valid = MigrationCheckConstraintCanonicalizer.canonicalizeOne(
                validDescriptor(expected, definition));

        assertThat(MigrationCheckConstraintCanonicalizer.canonicalizeOne(
                descriptor(expected, "f", definition, true, true, 0, false, false))).isNotEqualTo(valid);
        assertThat(MigrationCheckConstraintCanonicalizer.canonicalizeOne(
                descriptor(expected, "c", definition, false, true, 0, false, false))).isNotEqualTo(valid);
        assertThat(MigrationCheckConstraintCanonicalizer.canonicalizeOne(
                descriptor(expected, "c", definition, true, false, 0, false, false))).isNotEqualTo(valid);
        assertThat(MigrationCheckConstraintCanonicalizer.canonicalizeOne(
                descriptor(expected, "c", definition, true, true, 1, false, false))).isNotEqualTo(valid);
        assertThat(MigrationCheckConstraintCanonicalizer.canonicalizeOne(
                descriptor(expected, "c", definition, true, true, 0, true, false))).isNotEqualTo(valid);
        assertThat(MigrationCheckConstraintCanonicalizer.canonicalizeOne(
                descriptor(expected, "c", definition, true, true, 0, false, true))).isNotEqualTo(valid);
    }

    @Test
    void normalizesOnlyRedundantDumpRestoreCastsForStaffAuditStatusCheck() {
        var source = auditStatusDescriptor(auditStatusSourceRendering());
        var restored = auditStatusDescriptor(auditStatusRestoredRendering());
        var changed = new MigrationCheckConstraintCanonicalizer.ConstraintDescriptor(
                restored.table(), restored.name(), restored.type(),
                restored.definition().replace("'TERMINATED'", "'ON_LEAVE'"),
                true, true, 0, false, false);

        assertThat(MigrationCheckConstraintCanonicalizer.canonicalizeOne(source))
                .isEqualTo(MigrationCheckConstraintCanonicalizer.canonicalizeOne(restored));
        assertThat(MigrationCheckConstraintCanonicalizer.canonicalizeOne(source))
                .isNotEqualTo(MigrationCheckConstraintCanonicalizer.canonicalizeOne(changed));
    }

    @Test
    void preservesStrictRawComparisonForUnrecognizedChecksAndNonCheckConstraints() {
        MigrationCheckConstraintCanonicalizer.ConstraintDescriptor unknownCheck = new
                MigrationCheckConstraintCanonicalizer.ConstraintDescriptor("example", "ck_extra", "c",
                "CHECK (quantity > 0)", true, true, 0, false, false);
        MigrationCheckConstraintCanonicalizer.ConstraintDescriptor changedUnknownCheck = new
                MigrationCheckConstraintCanonicalizer.ConstraintDescriptor("example", "ck_extra", "c",
                "CHECK (quantity >= 0)", true, true, 0, false, false);
        MigrationCheckConstraintCanonicalizer.ConstraintDescriptor foreignKey = new
                MigrationCheckConstraintCanonicalizer.ConstraintDescriptor("example", "fk_example", "f",
                "FOREIGN KEY (user_id) REFERENCES users(user_id)", true, true, 0, false, false);
        MigrationCheckConstraintCanonicalizer.ConstraintDescriptor changedForeignKey = new
                MigrationCheckConstraintCanonicalizer.ConstraintDescriptor("example", "fk_example", "f",
                "FOREIGN KEY (user_id) REFERENCES accounts(user_id)", true, true, 0, false, false);

        assertThat(MigrationCheckConstraintCanonicalizer.canonicalizeOne(unknownCheck))
                .isEqualTo(unknownCheck.rawDescriptor());
        assertThat(MigrationCheckConstraintCanonicalizer.canonicalizeOne(unknownCheck))
                .isNotEqualTo(MigrationCheckConstraintCanonicalizer.canonicalizeOne(changedUnknownCheck));
        assertThat(MigrationCheckConstraintCanonicalizer.canonicalizeOne(foreignKey))
                .isNotEqualTo(MigrationCheckConstraintCanonicalizer.canonicalizeOne(changedForeignKey));
    }

    @Test
    void preservesUnknownKnownNameExpressionAsRawDescriptor() {
        MigrationCheckConstraintCanonicalizer.ExpectedCheck expected = expected("ck_users_role");
        String unexpected = "CHECK (role IS NOT NULL)";
        MigrationCheckConstraintCanonicalizer.ConstraintDescriptor descriptor = validDescriptor(expected, unexpected);

        String unverified = MigrationCheckConstraintCanonicalizer.canonicalizeOne(descriptor);
        assertThat(unverified).isEqualTo(descriptor.rawDescriptor() +
                "|validated=true|local=true|inheritanceCount=0|noInherit=false|hasParent=false");
        assertThat(unverified).isNotEqualTo(MigrationCheckConstraintCanonicalizer.canonicalizeOne(
                validDescriptor(expected, sourceRendering(expected))));
        List<MigrationCheckConstraintCanonicalizer.ConstraintDescriptor> allChecks =
                new ArrayList<>(MigrationCheckConstraintCanonicalizer.expectedChecks().stream()
                        .map(check -> check.name().equals(expected.name()) ? descriptor
                                : validDescriptor(check, sourceRendering(check)))
                        .toList());
        allChecks.add(auditStatusDescriptor(auditStatusSourceRendering()));
        assertThat(MigrationCheckConstraintCanonicalizer.canonicalize(allChecks)
                .unverifiedExpectedChecks()).hasSize(1).allMatch(value -> value.contains(unexpected));
    }

    @Test
    void rejectsMissingExpectedMigrationConstraint() {
        List<MigrationCheckConstraintCanonicalizer.ConstraintDescriptor> incomplete =
                new ArrayList<>(MigrationCheckConstraintCanonicalizer.expectedChecks().stream()
                        .filter(check -> !check.name().equals("ck_users_role"))
                        .map(check -> validDescriptor(check, sourceRendering(check)))
                        .toList());
        incomplete.add(auditStatusDescriptor(auditStatusSourceRendering()));

        assertThatThrownBy(() -> MigrationCheckConstraintCanonicalizer.canonicalizeAll(incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ck_users_role");
    }

    private static MigrationCheckConstraintCanonicalizer.ExpectedCheck expected(String name) {
        return MigrationCheckConstraintCanonicalizer.expectedChecks().stream()
                .filter(check -> check.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static MigrationCheckConstraintCanonicalizer.ConstraintDescriptor validDescriptor(
            MigrationCheckConstraintCanonicalizer.ExpectedCheck expected, String definition) {
        return descriptor(expected, "c", definition, true, true, 0, false, false);
    }

    private static MigrationCheckConstraintCanonicalizer.ConstraintDescriptor descriptor(
            MigrationCheckConstraintCanonicalizer.ExpectedCheck expected, String type, String definition,
            boolean validated, boolean local, int inheritanceCount, boolean noInherit, boolean hasParent) {
        return new MigrationCheckConstraintCanonicalizer.ConstraintDescriptor(expected.table(), expected.name(),
                type, definition, validated, local, inheritanceCount, noInherit, hasParent);
    }

    private static void assertRejected(MigrationCheckConstraintCanonicalizer.ExpectedCheck expected,
                                       String definition) {
        assertThat(MigrationCheckConstraintCanonicalizer.canonicalizeOne(validDescriptor(expected, definition)))
                .isNotEqualTo(MigrationCheckConstraintCanonicalizer.canonicalizeOne(
                        validDescriptor(expected, sourceRendering(expected))));
    }

    private static MigrationCheckConstraintCanonicalizer.ConstraintDescriptor auditStatusDescriptor(
            String definition) {
        return new MigrationCheckConstraintCanonicalizer.ConstraintDescriptor(
                "staff_profile_audit", "ck_staff_profile_audit_status", "c", definition,
                true, true, 0, false, false);
    }

    private static String auditStatusSourceRendering() {
        String values = "ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, " +
                "'SUSPENDED'::character varying, 'TERMINATED'::character varying]::text[]";
        return "CHECK ((previous_status IS NULL OR previous_status::text = ANY (" + values + ")) " +
                "AND (new_status IS NULL OR new_status::text = ANY (" + values + ")))";
    }

    private static String auditStatusRestoredRendering() {
        String values = "ARRAY['PENDING'::character varying::text, 'ACTIVE'::character varying::text, " +
                "'SUSPENDED'::character varying::text, 'TERMINATED'::character varying::text]";
        return "CHECK ((previous_status IS NULL OR previous_status::text = ANY (" + values + ")) " +
                "AND (new_status IS NULL OR new_status::text = ANY (" + values + ")))";
    }

    private static String sourceRendering(MigrationCheckConstraintCanonicalizer.ExpectedCheck expected) {
        String values = expected.allowedValues().stream()
                .map(value -> "'" + value + "'::character varying")
                .collect(java.util.stream.Collectors.joining(", "));
        return "CHECK (" + expected.column() + "::text = ANY (ARRAY[" + values + "]::text[]))";
    }

    private static String restoredRendering(MigrationCheckConstraintCanonicalizer.ExpectedCheck expected) {
        String values = expected.allowedValues().stream()
                .map(value -> "'" + value + "'::character varying::text")
                .collect(java.util.stream.Collectors.joining(", "));
        return "CHECK (" + expected.column() + "::text = ANY (ARRAY[" + values + "]))";
    }
}
