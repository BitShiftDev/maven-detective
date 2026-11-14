package com.acme.shop.gateway;

import com.acme.shop.commons.OrderJson;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;

/** Nimmt Bestellungen an und reicht sie weiter. */
public final class Gateway {

    private final Map<String, Object> routes;

    public Gateway(String routesYaml) {
        this.routes = new Yaml().load(routesYaml);
    }

    public ObjectNode accept(String orderId) {
        ObjectNode order = OrderJson.newOrder(orderId);
        order.put("route", String.valueOf(routes.getOrDefault("orders", "order-service")));
        return order;
    }
}
