package com.example.inventix.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Title and description for the generated OpenAPI document (/v3/api-docs, shown at /swagger-ui.html).
 * The behaviour rules (stock, status changes, errors) are documented in docs/API.md.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI inventixOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Inventix API")
                .version("v2")
                .description("Inventory, orders and stock ledger for the Inventix desktop app. "
                        + "See docs/API.md for the stock rules, status transitions and error codes."));
    }
}
