package io.github.bitshiftdev.mavendetective.core.catalog;

import io.github.bitshiftdev.mavendetective.core.BuildDoctor;
import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.check.Checks;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
import io.github.bitshiftdev.mavendetective.core.io.MavenLogParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Projekteigene Kataloge: {@link RuleCatalog#merge}, Ablehnung kaputter Regeln, Sprache der Texte. */
class RuleCatalogTest {

    @TempDir
    Path tmp;

    @AfterEach
    void resetLanguage() {
        Texts.setSource(null);
    }

    private static String catalog(int version, String... rules) {
        StringBuilder sb = new StringBuilder("version: " + version + "\nrules:\n");
        for (String r : rules) {
            sb.append(r);
        }
        return sb.toString();
    }

    private static String rule(String id, int priority, String pattern, String extra) {
        return "  - id: " + id + "\n"
                + "    number: 20\n"
                + "    category: Eigene Regel\n"
                + "    priority: " + priority + "\n"
                + "    patterns:\n"
                + "      - '" + pattern + "'\n"
                + "    cause: 'Eigene Ursache {artifact|}.'\n"
                + extra;
    }

    private Environment env() {
        return Environment.builder().userHome(tmp).projectDirectory(tmp).env(new HashMap<String, String>()).build();
    }

    @Test
    void ruleWithTheSameIdReplacesTheBuiltInOneInPlace() {
        RuleCatalog base = RuleCatalog.loadDefault();
        int index = base.getRules().indexOf(base.rule("corrupt-jar"));
        RuleCatalog extra = RuleCatalog.parse(catalog(1, rule("corrupt-jar", 90, "zip END header not found", "")), "extra.yaml");
        RuleCatalog merged = base.merge(extra);
        assertEquals(base.getRules().size(), merged.getRules().size());
        assertEquals("Eigene Ursache {artifact|}.", merged.rule("corrupt-jar").getCause());
        assertEquals(index, merged.getRules().indexOf(merged.rule("corrupt-jar")), "Reihenfolge bleibt");
    }

    @Test
    void newRuleIsAppendedAndTheHigherVersionWins() {
        RuleCatalog base = RuleCatalog.loadDefault();
        RuleCatalog merged = base.merge(RuleCatalog.parse(catalog(7, rule("acme-license", 99, "License header missing", "")), "x"));
        assertEquals(base.getRules().size() + 1, merged.getRules().size());
        assertEquals("acme-license", merged.getRules().get(merged.getRules().size() - 1).getId());
        assertEquals(7, merged.getVersion());
    }

    @Test
    void mergedRuleWinsByPriority() {
        RuleCatalog merged = RuleCatalog.loadDefault().merge(RuleCatalog.parse(catalog(1,
                rule("acme-nexus-down", 99, "from/to acme-nexus \\(", "")), "x"));
        List<Diagnosis> all = new BuildDoctor(merged, Checks.defaults()).diagnose(MavenLogParser.parse(
                "[ERROR] Could not transfer artifact a:b:pom:1 from/to acme-nexus (https://nexus.acme.example): status code: 401"), env());
        assertEquals("acme-nexus-down", all.get(0).getRuleId());
        assertEquals("repository-access-denied", all.get(1).getRuleId());
    }

    @Test
    void unknownFixTypeIsRejectedWithTheRuleId() {
        String yaml = catalog(1, rule("acme", 50, "x", "    fix:\n      type: reboot\n      label: 'Neu starten'\n"));
        RuleCatalog.CatalogException e = assertThrows(RuleCatalog.CatalogException.class, () -> RuleCatalog.parse(yaml, "extra.yaml"));
        assertTrue(e.getMessage().contains("[acme]") && e.getMessage().contains("'reboot'"), e.getMessage());
    }

    @Test
    void unknownCheckIsRejectedWhenTheDoctorIsBuilt() {
        RuleCatalog merged = RuleCatalog.loadDefault().merge(RuleCatalog.parse(catalog(1,
                rule("acme", 50, "x", "    checks: [crystal-ball]\n")), "x"));
        RuleCatalog.CatalogException e = assertThrows(RuleCatalog.CatalogException.class, () -> new BuildDoctor(merged, Checks.defaults()));
        assertTrue(e.getMessage().contains("'acme'") && e.getMessage().contains("'crystal-ball'"), e.getMessage());
    }

    @Test
    void brokenRulesAreRejectedWithTheirPlace() {
        String[][] cases = {
            {catalog(1, rule("acme", 50, "x", ""), rule("acme", 50, "y", "")), "doppelt"},
            {catalog(1, rule("acme", 50, "(unclosed", "")), "ungültige Regex"},
            {catalog(1, rule("Acme_Rule", 50, "x", "")), "Acme_Rule"},
            {catalog(1, rule("acme", 50, "x", "    colour: red\n")), "'colour'"},
            {catalog(1, rule("acme", 50, "x", "    confidence: maybe\n")), "confidence"},
        };
        for (String[] c : cases) {
            RuleCatalog.CatalogException e = assertThrows(RuleCatalog.CatalogException.class, () -> RuleCatalog.parse(c[0], "extra.yaml"));
            assertTrue(e.getMessage().contains("extra.yaml") && e.getMessage().contains(c[1]), e.getMessage());
        }
    }

    /**
     * Projekteigene Regeln dürfen einsprachig sein: ohne {@code _en} zeigt auch die englische Oberfläche den
     * Grundtext. Nur der eingebaute Katalog muss jeden Text auf Englisch haben ({@code TextsTest}).
     */
    @Test
    void projectRuleWithoutEnglishFallsBackToTheBaseText() {
        RuleCatalog extra = RuleCatalog.parse(catalog(1, rule("acme", 50, "x",
                "    cause_en: 'Own cause.'\n")), "x");
        Texts.setSource(() -> "en");
        assertEquals("Eigene Regel", extra.rule("acme").getCategory());
        assertEquals("Own cause.", extra.rule("acme").getCause());
        Texts.setSource(() -> "de");
        assertEquals("Eigene Ursache {artifact|}.", extra.rule("acme").getCause());
    }
}
