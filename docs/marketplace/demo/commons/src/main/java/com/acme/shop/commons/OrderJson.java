package com.acme.shop.commons;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

/** Gemeinsame JSON-Helfer. Kompiliert gegen Jackson 2.18 – {@code withObjectProperty} gibt es erst seit 2.16. */
public final class OrderJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OrderJson() {
    }

    public static ObjectNode newOrder(String id) {
        ObjectNode order = MAPPER.createObjectNode();
        order.put("id", id);
        order.withObjectProperty("customer").put("tier", "standard");
        return order;
    }

    public static List<String> channels() {
        return ImmutableList.of("web", "mobile", "partner");
    }

    public static Map<String, Object> defaults(String yaml) {
        return new Yaml().load(yaml);
    }
}
