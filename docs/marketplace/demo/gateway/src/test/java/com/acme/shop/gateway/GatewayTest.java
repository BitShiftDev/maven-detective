package com.acme.shop.gateway;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayTest {

    @Test
    void routesOrdersToTheOrderService() {
        ObjectNode order = new Gateway("orders: order-service").accept("A-1001");
        assertEquals("order-service", order.get("route").asText());
        assertEquals("standard", order.get("customer").get("tier").asText());
    }
}
