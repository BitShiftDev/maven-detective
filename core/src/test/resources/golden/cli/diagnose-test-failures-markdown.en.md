exit 0
### Maven build failed: Tests failed

**Cause** (certain): Tests failed – that is a test result, not a problem with Maven or dependencies.

**Evidence**

- `Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test (default-test) on project cart: There are test failures.`
- `Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.05 s <<< FAILURE! -- in com.acme.cart.CartTest`

**Suggested fix:** Look at the failed tests in target/surefire-reports

**Environment**

- OS: <os>

_Created with Maven Detective_
