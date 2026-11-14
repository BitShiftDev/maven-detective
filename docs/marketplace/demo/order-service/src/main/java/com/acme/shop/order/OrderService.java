package com.acme.shop.order;

import com.acme.shop.commons.OrderJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jsonwebtoken.jackson.io.JacksonSerializer;
import org.apache.http.HttpHost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** Legt Bestellungen an und meldet sie an die Abrechnung. */
public final class OrderService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);

    // HttpHost kommt aus httpcore – nur transitiv über httpclient da (undeklariert)
    private final HttpHost billing = new HttpHost("billing.internal", 8080, "http");
    private final ObjectMapper mapper = new ObjectMapper();
    private final JacksonSerializer<Map<String, ?>> tokens = new JacksonSerializer<>(mapper);

    public ObjectNode create(String id) {
        ObjectNode order = OrderJson.newOrder(id);
        LOG.info("Order {} created, billing at {}", id, billing);
        return order;
    }

    public CloseableHttpClient client() {
        return HttpClients.createDefault();
    }

    public JacksonSerializer<Map<String, ?>> tokens() {
        return tokens;
    }
}
