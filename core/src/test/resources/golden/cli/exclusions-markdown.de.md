exit 3
### Exclusion-Hygiene

Exclusion-Hygiene: 1 Exclusion in 3 POMs – 1 veraltet, 0 doppelt, 0 nicht prüfbar

| Befund | POM | Dependency | Exclusion | Erklärung |
|---|---|---|---|---|
| veraltet | `a:15` | `org.web:web` | `org.gone:gone` | Exclusion org.gone:gone schließt nichts aus: org.web:web:1.0 bringt org.gone:gone nicht (mehr) transitiv mit – kann weg. |
