package com.example.inventix;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starts the whole application (H2, JPA, Spring Batch, seed data) and runs one order through the real HTTP API.
 * Keep all full-context tests in this class: every @SpringBootTest context shares the same in-memory H2 database.
 * open-in-view is forced off so these requests prove the API never lazy-loads after the service transaction.
 */
@SpringBootTest(properties = "spring.jpa.open-in-view=false")
@AutoConfigureMockMvc
class InventixApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void contextLoads() {
    }

    @Test
    void openApiDocument_describesTheApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Inventix API"))
                .andExpect(jsonPath("$.paths['/api/products'].get").exists())
                .andExpect(jsonPath("$.paths['/api/orders'].post").exists());
    }

    @Test
    void swaggerUi_isServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    void orderLifecycle_overHttp_reservesAndRestoresStock() throws Exception {
        String productJson = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"E2E Widget\",\"sku\":\"E2E-001\",\"description\":\"End-to-end test product\","
                                + "\"price\":12.50,\"quantity\":10}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int productId = objectMapper.readTree(productJson).get("id").asInt();

        String orderJson = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":" + productId + ",\"quantity\":4}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(50.0))
                .andReturn().getResponse().getContentAsString();
        int orderId = objectMapper.readTree(orderJson).get("id").asInt();

        // Reading the order in a new request must include its items even though open-in-view is off.
        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderItems", hasSize(1)))
                .andExpect(jsonPath("$.orderItems[0].orderId").value(orderId))
                .andExpect(jsonPath("$.orderItems[0].productId").value(productId))
                .andExpect(jsonPath("$.orderItems[0].productName").value("E2E Widget"))
                .andExpect(jsonPath("$.orderItems[0].subtotal").value(50.0))
                .andExpect(jsonPath("$.orderItems[0].order").doesNotExist());

        // Lists load each order's items too.
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + orderId + ")].orderItems[0].productId").value(productId));
        mockMvc.perform(get("/api/orders").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + orderId + ")].orderItems[0].quantity").value(4));
        mockMvc.perform(get("/api/orders/{id}/items", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productName").value("E2E Widget"));

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(jsonPath("$.quantity").value(6));

        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(jsonPath("$.quantity").value(10));

        // CANCELLED is final.
        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isConflict());

        // The product is still referenced by the cancelled order.
        mockMvc.perform(delete("/api/products/{id}", productId))
                .andExpect(status().isConflict());
    }

    @Test
    void stockLedger_overHttp_recordsEveryChange_andAddsUpToTheQuantity() throws Exception {
        String productJson = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ledger Widget\",\"sku\":\"LEDGER-001\",\"price\":2.00,\"quantity\":20}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int productId = objectMapper.readTree(productJson).get("id").asInt();

        // Sell 5, then cancel that order: SALE -5, CANCEL +5.
        String orderJson = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":" + productId + ",\"quantity\":5}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int orderId = objectMapper.readTree(orderJson).get("id").asInt();
        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk());

        // Sell 3 on an order that stays pending: SALE -3.
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":" + productId + ",\"quantity\":3}]}"))
                .andExpect(status().isCreated());

        // A manual adjustment needs a note.
        mockMvc.perform(patch("/api/products/{id}/stock", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":-2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("note"));
        mockMvc.perform(patch("/api/products/{id}/stock", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":-2,\"note\":\"Broken in transit\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(15));

        // Editing the product can't change its stock behind the ledger's back.
        mockMvc.perform(put("/api/products/{id}", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ledger Widget\",\"sku\":\"LEDGER-001\",\"price\":2.00,\"quantity\":99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quantity"));
        mockMvc.perform(put("/api/products/{id}", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ledger Widget v2\",\"sku\":\"LEDGER-001\",\"price\":2.50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(15));

        String movementsJson = mockMvc.perform(get("/api/products/{id}/movements", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                // Newest first.
                .andExpect(jsonPath("$[0].reason").value("ADJUSTMENT"))
                .andExpect(jsonPath("$[0].delta").value(-2))
                .andExpect(jsonPath("$[0].note").value("Broken in transit"))
                .andExpect(jsonPath("$[4].note").value("Initial stock"))
                .andExpect(jsonPath("$[4].delta").value(20))
                .andReturn().getResponse().getContentAsString();

        int sum = 0;
        for (var movement : objectMapper.readTree(movementsJson)) {
            sum += movement.get("delta").asInt();
            assertThat(movement.get("productId").asInt()).isEqualTo(productId);
            assertThat(movement.has("product")).isFalse();
        }
        assertThat(sum).isEqualTo(15);

        mockMvc.perform(get("/api/stock-movements")
                        .param("productId", String.valueOf(productId))
                        .param("reason", "CANCEL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].delta").value(5))
                .andExpect(jsonPath("$[0].orderId").value(orderId));
    }

    @Test
    void missingOrder_returns404ErrorBody() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/orders/999999"));
    }

    private int postForId(String url, String json) throws Exception {
        String body = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asInt();
    }

    @Test
    void purchaseOrder_overHttp_receivesInParts() throws Exception {
        int supplierId = postForId("/api/suppliers", "{\"name\":\"E2E Supplier\",\"email\":\"orders@e2e.test\"}");
        int productId = postForId("/api/products", "{\"name\":\"E2E Bolt\",\"sku\":\"E2E-BOLT\",\"price\":0.50,"
                + "\"quantity\":3,\"supplierId\":" + supplierId + ",\"reorderPoint\":5,\"reorderQuantity\":20}");

        String created = mockMvc.perform(post("/api/purchase-orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":" + supplierId + ",\"lines\":[{\"productId\":" + productId
                                + ",\"quantity\":20,\"unitCost\":0.20}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.supplierName").value("E2E Supplier"))
                .andExpect(jsonPath("$.totalCost").value(4.0))
                .andExpect(jsonPath("$.lines[0].productSku").value("E2E-BOLT"))
                .andReturn().getResponse().getContentAsString();
        int poId = objectMapper.readTree(created).get("id").asInt();
        int lineId = objectMapper.readTree(created).get("lines").get(0).get("id").asInt();

        mockMvc.perform(patch("/api/purchase-orders/{id}/status", poId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ORDERED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ORDERED"));

        mockMvc.perform(post("/api/purchase-orders/{id}/receipts", poId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"lineId\":" + lineId + ",\"quantity\":15}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECEIVED"))
                .andExpect(jsonPath("$.lines[0].quantityRemaining").value(5));

        mockMvc.perform(post("/api/purchase-orders/{id}/receipts", poId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"lineId\":" + lineId + ",\"quantity\":6}]}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(jsonPath("$.quantity").value(18))
                .andExpect(jsonPath("$.supplierId").value(supplierId))
                .andExpect(jsonPath("$.reorderPoint").value(5));

        mockMvc.perform(get("/api/products/{id}/movements", productId))
                .andExpect(jsonPath("$[0].reason").value("RECEIPT"))
                .andExpect(jsonPath("$[0].purchaseOrderId").value(poId));

        mockMvc.perform(get("/api/purchase-orders").param("supplierId", String.valueOf(supplierId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].lines[0].quantityReceived").value(15));
    }

    @Test
    void productionRun_overHttp_addsStockWhenCompleted() throws Exception {
        int productId = postForId("/api/products", "{\"name\":\"E2E Table\",\"sku\":\"E2E-TABLE\",\"price\":99,"
                + "\"quantity\":0,\"source\":\"MAKE\"}");
        String created = mockMvc.perform(post("/api/production-runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + productId + ",\"quantity\":4}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.productSku").value("E2E-TABLE"))
                .andReturn().getResponse().getContentAsString();
        int runId = objectMapper.readTree(created).get("id").asInt();

        mockMvc.perform(patch("/api/production-runs/{id}/status", runId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(jsonPath("$.quantity").value(4))
                .andExpect(jsonPath("$.source").value("MAKE"));
        mockMvc.perform(get("/api/products/{id}/movements", productId))
                .andExpect(jsonPath("$[0].reason").value("PRODUCTION"))
                .andExpect(jsonPath("$[0].productionRunId").value(runId));
        mockMvc.perform(delete("/api/production-runs/{id}", runId)).andExpect(status().isConflict());
    }

    @Test
    void restock_overHttp_fillsADraftPurchaseOrder() throws Exception {
        int supplierId = postForId("/api/suppliers", "{\"name\":\"E2E Restock Supplier\"}");
        int productId = postForId("/api/products", "{\"name\":\"E2E Nut\",\"sku\":\"E2E-NUT\",\"price\":0.10,"
                + "\"quantity\":1,\"supplierId\":" + supplierId + ",\"reorderPoint\":10,\"reorderQuantity\":100}");

        mockMvc.perform(get("/api/reorder-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sku == 'E2E-NUT')].supplierName").value("E2E Restock Supplier"));

        mockMvc.perform(post("/api/products/{id}/restock", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("PURCHASE_ORDER"))
                .andExpect(jsonPath("$.created").value(true));

        mockMvc.perform(get("/api/reorder-list"))
                .andExpect(jsonPath("$[?(@.sku == 'E2E-NUT')].onOrder").value(100))
                .andExpect(jsonPath("$[?(@.sku == 'E2E-NUT')].covered").value(true));
    }

    @Test
    void reportsAndCsvExports_overHttp() throws Exception {
        int categoryId = postForId("/api/categories", "{\"name\":\"E2E Tools\"}");
        postForId("/api/products", "{\"name\":\"E2E Hammer, steel\",\"sku\":\"E2E-HAM\",\"price\":12.50,"
                + "\"quantity\":4,\"categoryId\":" + categoryId + ",\"unitCost\":7.25}");

        mockMvc.perform(get("/api/categories"))
                .andExpect(jsonPath("$[?(@.name == 'E2E Tools')].productCount").value(1));
        mockMvc.perform(get("/api/products").param("categoryId", String.valueOf(categoryId)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].unitCost").value(7.25));
        mockMvc.perform(get("/api/reports/stock-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[?(@.categoryName == 'E2E Tools')].valueAtCost").value(29.0));
        mockMvc.perform(get("/api/reports/movements").param("from", "2000-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.reason == 'ADJUSTMENT')].unitsIn").exists());
        mockMvc.perform(get("/api/reports/top-sellers").param("limit", "5")).andExpect(status().isOk());
        mockMvc.perform(get("/api/stock-movements").param("from", "2000-01-01").param("to", "2999-12-31"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Total-Count"));

        String csv = mockMvc.perform(get("/api/export/products.csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment; filename=\"products-")))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFFid,sku,name,category,");
        assertThat(csv).contains("E2E-HAM,\"E2E Hammer, steel\",E2E Tools,BUY,,12.50,7.25,4,,,50.00,29.00");

        mockMvc.perform(get("/api/export/stock-movements.csv").param("from", "2000-01-01")).andExpect(status().isOk());
        mockMvc.perform(get("/api/export/stock-value.csv")).andExpect(status().isOk());
        mockMvc.perform(get("/api/export/top-sellers.csv")).andExpect(status().isOk());
        mockMvc.perform(delete("/api/categories/{id}", categoryId)).andExpect(status().isConflict());
    }

    @Test
    void productionRun_overHttp_isAllOrNothingWhenAComponentIsShort() throws Exception {
        int tableId = postForId("/api/products", "{\"name\":\"E2E BOM Table\",\"sku\":\"E2E-BOM-TBL\",\"price\":99,"
                + "\"quantity\":0,\"source\":\"MAKE\"}");
        int legId = postForId("/api/products", "{\"name\":\"E2E BOM A Leg\",\"sku\":\"E2E-BOM-LEG\",\"price\":1,\"quantity\":100}");
        int topId = postForId("/api/products", "{\"name\":\"E2E BOM B Top\",\"sku\":\"E2E-BOM-TOP\",\"price\":1,\"quantity\":1}");
        mockMvc.perform(put("/api/products/{id}/components", tableId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"componentId\":" + legId + ",\"quantity\":4},"
                                + "{\"componentId\":" + topId + ",\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value("E2E-BOM-LEG"))
                .andExpect(jsonPath("$[0].inStock").value(100));
        int runId = postForId("/api/production-runs", "{\"productId\":" + tableId + ",\"quantity\":2}");

        // Legs are used up first (by name), then the tops run out: the leg consumption must be undone too.
        mockMvc.perform(patch("/api/production-runs/{id}/status", runId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("E2E BOM B Top")));

        mockMvc.perform(get("/api/products/{id}", legId)).andExpect(jsonPath("$.quantity").value(100));
        mockMvc.perform(get("/api/products/{id}", tableId)).andExpect(jsonPath("$.quantity").value(0));
        mockMvc.perform(get("/api/production-runs/{id}", runId)).andExpect(jsonPath("$.status").value("PLANNED"));
    }

    @Test
    void cancelRemaining_overHttp() throws Exception {
        int supplierId = postForId("/api/suppliers", "{\"name\":\"E2E Cancel Supplier\"}");
        int productId = postForId("/api/products", "{\"name\":\"E2E Washer\",\"sku\":\"E2E-WASH\",\"price\":0.05,\"quantity\":0}");
        String created = mockMvc.perform(post("/api/purchase-orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":" + supplierId + ",\"lines\":[{\"productId\":" + productId + ",\"quantity\":10}]}"))
                .andReturn().getResponse().getContentAsString();
        int poId = objectMapper.readTree(created).get("id").asInt();
        int lineId = objectMapper.readTree(created).get("lines").get(0).get("id").asInt();
        mockMvc.perform(patch("/api/purchase-orders/{id}/status", poId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ORDERED\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/purchase-orders/{id}/receipts", poId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"lineId\":" + lineId + ",\"quantity\":4}]}")).andExpect(status().isOk());

        mockMvc.perform(post("/api/purchase-orders/{id}/lines/{lineId}/cancel-remaining", poId, lineId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.lines[0].quantityCancelled").value(6))
                .andExpect(jsonPath("$.lines[0].quantityRemaining").value(0));
    }
}

