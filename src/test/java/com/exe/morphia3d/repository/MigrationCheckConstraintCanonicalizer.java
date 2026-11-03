package com.exe.morphia3d.repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Set;

/** Test-only canonicalization for the exact migration string-list CHECK constraints. */
final class MigrationCheckConstraintCanonicalizer {
    private static final List<ExpectedCheck> EXPECTED_CHECKS = List.of(
            check("users", "ck_users_role", "role", "ADMIN", "USER", "DESIGNER"),
            check("users", "ck_users_status", "status", "ACTIVE", "UNACTIVE"),
            check("printers", "ck_printers_status", "status", "ACTIVE", "FIXED", "INACTIVE"),
            check("materials", "ck_materials_is_available", "is_available", "AVAILABLE", "UNAVAILABLE"),
            check("model_3d", "ck_model_3d_source_type", "source_type",
                    "DESIGNER_MODEL", "AI_MODEL", "CUSTOMER_CUSTOMIZED"),
            check("model_3d", "ck_model_3d_model_status", "model_status", "PENDING", "ACTIVE", "INACTIVE"),
            check("orders", "ck_orders_status", "status", "PENDING", "PROCESSING", "SUCCEEDED", "CANCELED"),
            check("payments", "ck_payments_method", "method", "VNPAY", "MOMO"),
            check("payments", "ck_payments_status", "status", "PENDING", "SUCCESS", "FAILED"),
            check("media_asset", "ck_media_asset_kind", "media_kind", "IMAGE", "MODEL_3D"),
            check("media_asset", "ck_media_asset_state", "state", "PENDING_UPLOAD", "UPLOADING", "READY",
                    "ATTACHED", "CLEANUP_PENDING", "CLEANUP_LEASED", "DELETED"),
            check("staff_profiles", "ck_staff_profiles_status", "status",
                    "PENDING", "ACTIVE", "SUSPENDED", "TERMINATED"),
            check("staff_profile_audit", "ck_staff_profile_audit_action", "action",
                    "CREATED", "UPDATED", "STATUS_CHANGED"));
    private static final Set<Key> RESTORE_CAST_EQUIVALENT_CHECKS = Set.of(
            new Key("staff_profile_audit", "ck_staff_profile_audit_status"));

    private MigrationCheckConstraintCanonicalizer() {
    }

    static List<ExpectedCheck> expectedChecks() {
        return EXPECTED_CHECKS;
    }

    static List<String> canonicalizeAll(List<ConstraintDescriptor> descriptors) {
        return canonicalize(descriptors).descriptors();
    }

    static Canonicalization canonicalize(List<ConstraintDescriptor> descriptors) {
        List<String> result = new ArrayList<>(descriptors.size());
        List<String> rawDefinitions = new ArrayList<>(descriptors.size());
        List<String> unverifiedExpectedChecks = new ArrayList<>();
        Set<Key> foundExpectedChecks = new HashSet<>();
        for (ConstraintDescriptor descriptor : descriptors) {
            Key key = new Key(descriptor.table(), descriptor.name());
            ExpectedCheck expected = expectedCheck(key);
            boolean expectedCastEquivalent = RESTORE_CAST_EQUIVALENT_CHECKS.contains(key);
            if ((expected != null || expectedCastEquivalent) && !foundExpectedChecks.add(key)) {
                throw new IllegalArgumentException("Duplicate expected migration CHECK constraint: " + key);
            }
            result.add(canonicalizeOne(descriptor));
            rawDefinitions.add(descriptor.rawDescriptor());
            if ((expected != null && !matchesExpectedContract(descriptor, expected)) ||
                    (expectedCastEquivalent && !hasExpectedCheckMetadata(descriptor))) {
                unverifiedExpectedChecks.add(descriptor.rawDescriptor() + "|validated=" + descriptor.validated() +
                        "|local=" + descriptor.local() + "|inheritanceCount=" + descriptor.inheritanceCount() +
                        "|noInherit=" + descriptor.noInherit() + "|hasParent=" + descriptor.hasParent());
            }
        }

        List<String> missing = java.util.stream.Stream.concat(
                        EXPECTED_CHECKS.stream().map(ExpectedCheck::key), RESTORE_CAST_EQUIVALENT_CHECKS.stream())
                .filter(key -> !foundExpectedChecks.contains(key))
                .map(Key::toString)
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing expected migration CHECK constraints: " + missing);
        }
        result.sort(String::compareTo);
        rawDefinitions.sort(String::compareTo);
        unverifiedExpectedChecks.sort(String::compareTo);
        return new Canonicalization(List.copyOf(result), List.copyOf(rawDefinitions),
                List.copyOf(unverifiedExpectedChecks));
    }

    static String canonicalizeOne(ConstraintDescriptor descriptor) {
        ExpectedCheck expected = expectedCheck(new Key(descriptor.table(), descriptor.name()));
        Key key = new Key(descriptor.table(), descriptor.name());
        if (expected == null && !RESTORE_CAST_EQUIVALENT_CHECKS.contains(key)) {
            return descriptor.rawDescriptor();
        }
        if (expected != null && !matchesExpectedContract(descriptor, expected)) {
            return descriptor.rawDescriptor() + "|validated=" + descriptor.validated() + "|local=" + descriptor.local() +
                    "|inheritanceCount=" + descriptor.inheritanceCount() + "|noInherit=" + descriptor.noInherit() +
                    "|hasParent=" + descriptor.hasParent();
        }
        if (expected == null) {
            if (!hasExpectedCheckMetadata(descriptor)) {
                return descriptor.rawDescriptor() + "|validated=" + descriptor.validated() + "|local=" + descriptor.local() +
                        "|inheritanceCount=" + descriptor.inheritanceCount() + "|noInherit=" + descriptor.noInherit() +
                        "|hasParent=" + descriptor.hasParent();
            }
            return descriptor.rawDescriptor().substring(0, descriptor.rawDescriptor().lastIndexOf('|') + 1) +
                    normalizePostgresRestoreCasts(descriptor.definition()) +
                    "|validated=true|local=true|inheritanceCount=0|noInherit=false|hasParent=false";
        }
        return "CONSTRAINT|" + expected.table() + "|" + expected.name() + "|c" +
                "|validated=true|local=true|inheritanceCount=0|noInherit=false|hasParent=false" +
                "|operator=IN|column=" + expected.column() + "|values=" + expected.allowedValues() +
                "|nullSemantics=UNKNOWN_PASSES";
    }

    private static boolean matchesExpectedContract(ConstraintDescriptor descriptor, ExpectedCheck expected) {
        return hasExpectedCheckMetadata(descriptor) &&
                (expectedSourceRendering(expected).equals(descriptor.definition()) ||
                        expectedRestoredRendering(expected).equals(descriptor.definition()));
    }

    private static boolean hasExpectedCheckMetadata(ConstraintDescriptor descriptor) {
        return "c".equals(descriptor.type()) && descriptor.validated() && descriptor.local() &&
                descriptor.inheritanceCount() == 0 && !descriptor.noInherit() && !descriptor.hasParent();
    }

    private static String normalizePostgresRestoreCasts(String definition) {
        return definition.replace("::character varying::text", "::character varying")
                .replace("]::text[]", "]");
    }

    private static ExpectedCheck expectedCheck(Key key) {
        return EXPECTED_CHECKS.stream().filter(check -> check.key().equals(key)).findFirst().orElse(null);
    }

    private static String expectedSourceRendering(ExpectedCheck expected) {
        String values = expected.allowedValues().stream()
                .map(value -> "'" + value + "'::character varying")
                .collect(java.util.stream.Collectors.joining(", "));
        return "CHECK (" + expected.column() + "::text = ANY (ARRAY[" + values + "]::text[]))";
    }

    private static String expectedRestoredRendering(ExpectedCheck expected) {
        String values = expected.allowedValues().stream()
                .map(value -> "'" + value + "'::character varying::text")
                .collect(java.util.stream.Collectors.joining(", "));
        return "CHECK (" + expected.column() + "::text = ANY (ARRAY[" + values + "]))";
    }

    private static ExpectedCheck check(String table, String name, String column, String... values) {
        return new ExpectedCheck(table, name, column, List.of(values));
    }

    record ExpectedCheck(String table, String name, String column, List<String> allowedValues) {
        ExpectedCheck {
            allowedValues = List.copyOf(allowedValues);
        }

        Key key() {
            return new Key(table, name);
        }
    }

    record ConstraintDescriptor(String table, String name, String type, String definition,
                                boolean validated, boolean local, int inheritanceCount,
                                boolean noInherit, boolean hasParent) {
        String rawDescriptor() {
            return String.join("|", "CONSTRAINT", table, name, type, definition);
        }
    }

    record Canonicalization(List<String> descriptors, List<String> rawDefinitions,
                            List<String> unverifiedExpectedChecks) {
    }

    private record Key(String table, String name) {
        @Override
        public String toString() {
            return table + "." + name;
        }
    }
}
