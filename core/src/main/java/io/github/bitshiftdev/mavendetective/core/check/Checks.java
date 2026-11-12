package io.github.bitshiftdev.mavendetective.core.check;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Register aller Prüfungen; der Katalog verweist per ID auf sie. */
public final class Checks {

    private final Map<String, Check> byId = new LinkedHashMap<String, Check>();

    public static Checks defaults() {
        Checks c = new Checks();
        c.register(new ResolutionChecks.OfflineMode());
        c.register(new ResolutionChecks.NetworkFailure());
        c.register(new ResolutionChecks.LastUpdatedFiles());
        c.register(new ResolutionChecks.ReactorSibling());
        c.register(new ResolutionChecks.RequestedRepository());
        for (Check check : RepositoryChecks.all()) {
            c.register(check);
        }
        for (Check check : PomChecks.all()) {
            c.register(check);
        }
        for (Check check : RuntimeChecks.all()) {
            c.register(check);
        }
        return c;
    }

    public Checks register(Check check) {
        if (byId.containsKey(check.id())) {
            throw new IllegalArgumentException("Prüfung doppelt registriert: " + check.id());
        }
        byId.put(check.id(), check);
        return this;
    }

    public Check get(String id) {
        return byId.get(id);
    }

    public Map<String, Check> all() {
        return Collections.unmodifiableMap(byId);
    }
}
