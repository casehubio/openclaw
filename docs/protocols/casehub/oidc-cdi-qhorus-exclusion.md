---
id: PP-20260623-c3244e
title: "Exclude QhorusInboundCurrentPrincipal from CDI when casehub-platform-oidc is on the classpath"
type: rule
scope: repo
applies_to: "app/pom.xml — any commit adding casehub-platform-oidc; app/src/main/resources/application.properties"
severity: critical
refs:
  - docs/superpowers/specs/2026-06-23-oidc-wiring-design.md
violation_hint: "AmbiguousResolutionException at augmentation: two unqualified CDI beans implement CurrentPrincipal — OidcCurrentPrincipal @RequestScoped and QhorusInboundCurrentPrincipal @ApplicationScoped"
garden_ref: "GE-20260623-22f1f7"
created: 2026-06-23
---

When `casehub-platform-oidc` is on the compile classpath, `OidcCurrentPrincipal @RequestScoped` and `QhorusInboundCurrentPrincipal @ApplicationScoped` are both active CDI beans implementing `CurrentPrincipal` with no qualifier disambiguation, causing `AmbiguousResolutionException` at augmentation. Add `io.casehub.qhorus.runtime.identity.QhorusInboundCurrentPrincipal` to `quarkus.arc.exclude-types` in `app/src/main/resources/application.properties`. The `QhorusInboundCurrentPrincipal` Javadoc claims `OidcCurrentPrincipal` has `@Priority(100)` and auto-displaces it — that annotation does not exist in the source (qhorus#301 filed to correct the Javadoc).
