exit 3
### Unused and undeclared dependencies

Dependency usage: 1 undeclared, 1 unused, 0 only in tests – 2 modules

| Module | Kind | Dependency | Evidence | Suggestion |
|---|---|---|---|---|
| `a` | undeclared | `slf4j-api:2.0` | `org.slf4j.Logger` | Declare org.slf4j:slf4j-api:2.0 in a |
| `a` | unused | `lib:1.0` | – | Remove org.unused:lib from a |
