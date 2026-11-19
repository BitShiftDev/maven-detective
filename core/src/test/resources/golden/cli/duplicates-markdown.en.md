exit 3
### Duplicate classes

Duplicate classes: 1 finding in 2 modules – 1 with different content, 1 on the runtime classpath

| Module | JARs (1st wins) | Classes | Content | Recommendation |
|---|---|---|---|---|
| `a` | `commons-logging:1.2`, `jcl-over-slf4j:1.7.36` | 1 (org.apache.commons.logging) | 1 different | Remove commons-logging:1.2: known pair – jcl-over-slf4j or spring-jcl replace commons-logging on purpose – the original has to go, otherwise the order decides which logging applies. |
