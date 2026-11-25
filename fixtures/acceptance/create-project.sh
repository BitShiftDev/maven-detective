#!/usr/bin/env bash
# Erzeugt das Abnahmeprojekt für das Plugin (runIde): Spring-Boot-Multi-Modul mit eingebauten
# Befunden für Modul A und je einem Git-Branch pro Kaputt-Variante für Build Doctor.
#
#   bash fixtures/acceptance/create-project.sh [zielverzeichnis]   # Standard: ~/Projekte/md-abnahme/spring-shop
#
# Ein vorhandenes Zielverzeichnis wird nicht angefasst (Abbruch). Danach einmal auf main
# `mvn -q test-compile` (lädt die Dependencies ins lokale Repository) und das Projekt in runIde öffnen.
#
# main – grün, mit Befunden:
#   A1  Versionen aus Parent (spring-boot-starter-parent 3.4), BOM-Import (jackson-bom, verliert gegen
#       die geerbte Verwaltung des Parents) und direkt
#   A2  guava 33.0.0-jre in shared, 33.3.1-jre in service → Abweichung, Pin im Root
#   A3  commons-logging 1.2 in service neben spring-jcl (über spring-core) → doppelte Klassen
#   A4  Exclusion commons-logging an spring-core in shared → veraltet (spring-core 6 bringt spring-jcl)
#   A5  web nutzt @RestController aus spring-web, deklariert nur spring-webmvc → nicht deklariert;
#       commons-io in shared ohne Nutzung → ungenutzt
#   A6  Parent 3.4.13 → 3.5.x simulieren
#
# Kaputt-Branches (jeweils von main):
#   kaputt/fehlender-parent     Parent-Version gibt es nicht              → invalid-pom (Banner, Karte)
#   kaputt/plugin-ohne-version  Plugin ohne <version>, nicht auflösbar    → plugin-missing + Quick-Fix Version einfügen
#   kaputt/no-such-method       Test bekommt guava 20.0 statt 33.x        → NoSuchMethodError, Regel 10 (mvn test)
#   kaputt/test-scope           commons-io nur test, main importiert es  → package … does not exist, set-scope compile
#   kaputt/offline              neue Dependency commons-csv               → mvn -o: nicht auflösbar (Branch nie online bauen)
set -euo pipefail

DEST="${1:-$HOME/Projekte/md-abnahme/spring-shop}"
if [[ -e "$DEST" ]]; then
  echo "$DEST existiert schon – zum Neuanlegen erst selbst entfernen." >&2
  exit 1
fi
mkdir -p "$DEST"
cd "$DEST"

BOOT=3.4.13
JACKSON=2.19.4

write() { mkdir -p "$(dirname "$1")"; cat > "$1"; }

# ------------------------------------------------------------------ main

write pom.xml <<XML
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>$BOOT</version>
    <relativePath/>
  </parent>

  <groupId>com.example.shop</groupId>
  <artifactId>spring-shop</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <modules>
    <module>shared</module>
    <module>service</module>
    <module>web</module>
  </modules>

  <properties>
    <java.version>17</java.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>com.fasterxml.jackson</groupId>
        <artifactId>jackson-bom</artifactId>
        <version>$JACKSON</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
XML

write shared/pom.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.example.shop</groupId>
    <artifactId>spring-shop</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>shared</artifactId>

  <dependencies>
    <dependency>
      <groupId>com.google.guava</groupId>
      <artifactId>guava</artifactId>
      <version>33.0.0-jre</version>
    </dependency>
    <dependency>
      <groupId>org.springframework</groupId>
      <artifactId>spring-core</artifactId>
      <exclusions>
        <exclusion>
          <groupId>commons-logging</groupId>
          <artifactId>commons-logging</artifactId>
        </exclusion>
      </exclusions>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>commons-io</groupId>
      <artifactId>commons-io</artifactId>
      <version>2.18.0</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
XML

write shared/src/main/java/com/example/shop/shared/Labels.java <<'JAVA'
package com.example.shop.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import org.springframework.util.StringUtils;

public final class Labels {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Labels() {
    }

    /** Strings.lenientFormat gibt es erst ab Guava 25.1 – Grundlage für kaputt/no-such-method. */
    public static String label(String name, int count) {
        return Strings.lenientFormat("%s (%s)", StringUtils.capitalize(name), count);
    }

    public static String json(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }
}
JAVA

write shared/src/test/java/com/example/shop/shared/LabelsTest.java <<'JAVA'
package com.example.shop.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LabelsTest {
    @Test
    void label() {
        assertEquals("Apfel (3)", Labels.label("apfel", 3));
    }
}
JAVA

write service/pom.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.example.shop</groupId>
    <artifactId>spring-shop</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>service</artifactId>

  <dependencies>
    <dependency>
      <groupId>com.example.shop</groupId>
      <artifactId>shared</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>com.google.guava</groupId>
      <artifactId>guava</artifactId>
      <version>33.3.1-jre</version>
    </dependency>
    <dependency>
      <groupId>commons-logging</groupId>
      <artifactId>commons-logging</artifactId>
      <version>1.2</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
XML

write service/src/main/java/com/example/shop/service/OrderService.java <<'JAVA'
package com.example.shop.service;

import com.example.shop.shared.Labels;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class OrderService {
    private static final Log LOG = LogFactory.getLog(OrderService.class);

    public List<String> lines() {
        LOG.debug("lines");
        return ImmutableList.of(Labels.label("apfel", 3), Labels.label("birne", 1));
    }
}
JAVA

write service/src/test/java/com/example/shop/service/OrderServiceTest.java <<'JAVA'
package com.example.shop.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OrderServiceTest {
    @Test
    void lines() {
        assertEquals(2, new OrderService().lines().size());
    }
}
JAVA

write web/pom.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.example.shop</groupId>
    <artifactId>spring-shop</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>web</artifactId>

  <dependencies>
    <dependency>
      <groupId>com.example.shop</groupId>
      <artifactId>service</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.springframework</groupId>
      <artifactId>spring-webmvc</artifactId>
    </dependency>
  </dependencies>
</project>
XML

write web/src/main/java/com/example/shop/web/OrderController.java <<'JAVA'
package com.example.shop.web;

import com.example.shop.service.OrderService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

@RestController
public class OrderController {
    private final OrderService service = new OrderService();

    @GetMapping("/orders")
    public List<String> orders() {
        return service.lines();
    }

    @GetMapping("/")
    public ModelAndView home() {
        return new ModelAndView("redirect:/orders");
    }
}
JAVA

write .gitignore <<'TXT'
target/
.idea/
*.iml
TXT

git init -q -b main
git add -A
git -c user.name=Abnahme -c user.email=abnahme@example.invalid commit -q -m "Abnahmeprojekt: grün, mit Befunden für A1–A6"

# ------------------------------------------------------------------ Kaputt-Branches

branch() {
  git checkout -q -b "$1" main
}
commit() {
  git add -A
  git -c user.name=Abnahme -c user.email=abnahme@example.invalid commit -q -m "$1"
}
# Ersetzt genau einen Textblock (python3 statt sed: mehrzeilig, ohne Escape-Fallen)
replace() {
  python3 - "$1" "$2" "$3" <<'PY'
import sys
path, old, new = sys.argv[1:]
s = open(path, encoding="utf-8").read()
if s.count(old) != 1:
    sys.exit(f"{path}: Text nicht genau einmal gefunden: {old!r}")
open(path, "w", encoding="utf-8").write(s.replace(old, new))
PY
}

branch kaputt/fehlender-parent
replace pom.xml "<version>$BOOT</version>" "<version>3.4.99</version>"
commit "Kaputt: Parent-Version gibt es nicht"

branch kaputt/plugin-ohne-version
replace web/pom.xml "  </dependencies>
</project>" "  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>com.example.missing</groupId>
        <artifactId>missing-maven-plugin</artifactId>
        <executions>
          <execution>
            <phase>validate</phase>
            <goals>
              <goal>run</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>"
commit "Kaputt: Plugin ohne Version, nicht auflösbar"

branch kaputt/no-such-method
# service deklariert guava nicht mehr selbst, verwaltet es aber auf 20.0 – shared wurde gegen 33.x
# kompiliert, der Test von service ruft Strings.lenientFormat (ab 25.1) → NoSuchMethodError
replace service/pom.xml "    <dependency>
      <groupId>com.google.guava</groupId>
      <artifactId>guava</artifactId>
      <version>33.3.1-jre</version>
    </dependency>
" ""
replace service/pom.xml "  <dependencies>" "  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>com.google.guava</groupId>
        <artifactId>guava</artifactId>
        <version>20.0</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>"
replace service/src/main/java/com/example/shop/service/OrderService.java "import com.google.common.collect.ImmutableList;
" ""
replace service/src/main/java/com/example/shop/service/OrderService.java "ImmutableList.of(" "java.util.Arrays.asList("
commit "Kaputt: guava 20.0 im Test von service (NoSuchMethodError)"

branch kaputt/test-scope
replace shared/pom.xml "      <version>2.18.0</version>
    </dependency>" "      <version>2.18.0</version>
      <scope>test</scope>
    </dependency>"
replace shared/src/main/java/com/example/shop/shared/Labels.java "import com.google.common.base.Strings;
" "import com.google.common.base.Strings;
import java.io.File;
import org.apache.commons.io.FileUtils;
"
replace shared/src/main/java/com/example/shop/shared/Labels.java "    public static String json(" "    public static String read(File file) throws Exception {
        return FileUtils.readFileToString(file, \"UTF-8\");
    }

    public static String json("
commit "Kaputt: commons-io nur im Scope test, main importiert es"

branch kaputt/offline
replace shared/pom.xml "    <dependency>
      <groupId>org.junit.jupiter</groupId>" "    <dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-csv</artifactId>
      <version>1.12.0</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>"
commit "Kaputt: neue Dependency – offline bauen (mvn -o)"

git checkout -q main
echo "Abnahmeprojekt in $DEST (Branches: $(git branch --format='%(refname:short)' | tr '\n' ' '))"
