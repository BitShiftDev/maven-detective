package com.acme.shop.billing;

import com.acme.shop.commons.OrderJson;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;

import java.util.List;

/** Erzeugt Rechnungen zu Bestellungen. */
public final class Invoices {

    public List<String> positions(String orderId) {
        ObjectNode order = OrderJson.newOrder(orderId);
        return ImmutableList.of(order.get("id").asText(), "shipping");
    }
}
