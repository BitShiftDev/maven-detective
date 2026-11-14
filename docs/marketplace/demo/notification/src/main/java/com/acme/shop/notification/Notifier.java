package com.acme.shop.notification;

import com.acme.shop.commons.OrderJson;
import com.google.common.base.Joiner;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;

/** Verschickt Nachrichten über alle Kanäle. */
public final class Notifier {

    public String channels() {
        return Joiner.on(", ").join(OrderJson.channels());
    }

    public Map<String, Object> templates(String yaml) {
        return new Yaml().load(yaml);
    }
}
