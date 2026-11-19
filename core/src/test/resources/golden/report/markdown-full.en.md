### Maven build failed: Artifact fehlt

**Cause** (certain): org.acme:lib:1.0 liegt in keinem Repository – der Fehlversuch ist gemerkt

**Evidence**

- `[ERROR] Failed to execute goal on project app: Could not resolve dependencies: org.acme:lib:jar:1.0 was not found in https://repo.maven.apache.org/maven2 during a previous attempt. This failure was cached in the local repository and resolution is not reattempted until the update interval of central…`
- Fehlversuch im lokalen Repository gemerkt -> /repo/org/acme/lib/1.0/lib-1.0.jar.lastUpdated
- Mirror „corp“ für * -> /home/u/.m2/settings.xml:12
- Maven 3.9.11, JDK 21
- Größe der Datei: 0 Bytes
- Deklaration -> /w/app/pom.xml:27
- wird auf der Karte nicht mehr gezeigt

**Suggested fix:** Fehlversuche löschen und neu bauen  (2 files)

**Since the last green build**

- JDK 21.0.4 → 17.0.2
- Dependency org.acme:lib 1.0 → 2.0

**Environment**

- Maven: 3.9.11
- JDK: 21.0.4 (/usr/lib/jvm/java-21)
- Offline: false
- settings.xml: /home/u/.m2/settings.xml
- Profiles: ci
- Goals: clean verify
- OS: <os>

_Created with Maven Detective_
