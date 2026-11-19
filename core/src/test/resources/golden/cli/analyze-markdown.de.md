exit 3
### Ungenutzte und undeklarierte Dependencies

Dependency-Nutzung: 1 undeklariert, 1 ungenutzt, 0 nur in Tests – 2 Module

| Modul | Art | Dependency | Beleg | Vorschlag |
|---|---|---|---|---|
| `a` | undeklariert | `slf4j-api:2.0` | `org.slf4j.Logger` | org.slf4j:slf4j-api:2.0 in a deklarieren |
| `a` | ungenutzt | `lib:1.0` | – | org.unused:lib aus a entfernen |
