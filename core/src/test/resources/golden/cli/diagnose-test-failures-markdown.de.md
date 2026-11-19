exit 0
### Maven-Build fehlgeschlagen: Tests fehlgeschlagen

**Ursache** (sicher): Tests sind fehlgeschlagen – das ist ein Testergebnis, kein Problem mit Maven oder Dependencies.

**Belege**

- `Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test (default-test) on project cart: There are test failures.`
- `Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.05 s <<< FAILURE! -- in com.acme.cart.CartTest`

**Vorgeschlagene Behebung:** Fehlgeschlagene Tests in target/surefire-reports ansehen

**Umgebung**

- OS: <os>

_Erstellt mit Maven Detective_
