# Upgrade Plan: morphia-3d

- Project: `d:\2Dto3D_BE`
- Session: `20260908052253`
- Goal: Upgrade Java runtime to Java 25, the latest LTS.
- Mode: Auto-execution

## Changes

- Installed JDK 25.0.2.
- Updated `pom.xml` property `java.version` to `25`.
- Preserved Spring Boot 4.1.0 and Maven configuration.
- Added the test-scoped H2 dependency needed for isolated context tests.

## Validation

- Java 25 `clean test-compile`: passed.
- Java 25 `clean verify`: passed with isolated H2 test settings.
- Commit: `d70ec8da16d0432a76ef3866e6c7c520d06054da`
- Branch: `appmod/java-upgrade-20260908052253`

## Limitation

The dedicated CVE scanner was unavailable during this upgrade session.
