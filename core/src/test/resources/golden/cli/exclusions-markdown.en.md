exit 3
### Exclusion hygiene

Exclusion hygiene: 1 exclusion in 3 POMs – 1 stale, 0 redundant, 0 uncheckable

| Finding | POM | Dependency | Exclusion | Explanation |
|---|---|---|---|---|
| stale | `a:15` | `org.web:web` | `org.gone:gone` | Exclusion org.gone:gone excludes nothing: org.web:web:1.0 does not bring org.gone:gone transitively (any more) – it can go. |
