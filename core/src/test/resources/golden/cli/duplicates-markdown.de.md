exit 3
### Doppelte Klassen

Doppelte Klassen: 1 Fund in 2 Modulen – 1 mit verschiedenem Inhalt, 1 im Laufzeit-Classpath

| Modul | JARs (1. gewinnt) | Klassen | Inhalt | Empfehlung |
|---|---|---|---|---|
| `a` | `commons-logging:1.2`, `jcl-over-slf4j:1.7.36` | 1 (org.apache.commons.logging) | 1 verschieden | commons-logging:1.2 entfernen: bekanntes Paar – jcl-over-slf4j bzw. spring-jcl ersetzen commons-logging absichtlich – das Original muss raus, sonst entscheidet die Reihenfolge, welches Logging greift. |
