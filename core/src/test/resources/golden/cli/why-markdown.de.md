exit 0
### Warum `com.google.guava:guava` 33.3.1-jre in `com.acme:a:1.0`?

Version 33.3.1-jre: direkt im Modul angegeben – eine direkte Angabe gewinnt immer, auch gegen dependencyManagement.

1. Direkt angegeben <version>33.3.1-jre</version> in a (a/pom.xml) — `$PROJECT/a/pom.xml:29`

### Warum `com.google.guava:guava` 32.1.3-jre in `com.acme:b:1.0`?

Version 32.1.3-jre: direkt im Modul angegeben – eine direkte Angabe gewinnt immer, auch gegen dependencyManagement.

1. Direkt angegeben <version>32.1.3-jre</version> in b (b/pom.xml) — `$PROJECT/b/pom.xml:13`
