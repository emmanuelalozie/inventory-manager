package com.example.inventix.config;

import com.example.inventix.model.Product;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Adds a few sample products on the first start (no products in the database) so the UI has something to show.
 * Turn it off with inventix.seed-data=false.
 */
@Component
@ConditionalOnProperty(name = "inventix.seed-data", havingValue = "true")
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final ProductRepository productRepository;
    private final ProductService productService;

    @Autowired
    public DataSeeder(ProductRepository productRepository, ProductService productService) {
        this.productRepository = productRepository;
        this.productService = productService;
    }

    @Override
    public void run(String... args) {
        // The database is a file now, so this runs on every start: only the first start (no products) seeds.
        long existing = productRepository.count();
        if (existing > 0) {
            log.debug("Skipping sample data: {} products already exist", existing);
            return;
        }
        List<Product> products = List.of(
                product("Wireless Mouse", "WM-001", "2.4 GHz wireless optical mouse", "24.99", 120),
                product("Mechanical Keyboard", "KB-002", "Tenkeyless keyboard with brown switches", "89.90", 35),
                product("USB-C Hub", "HUB-003", "7-in-1 USB-C hub with HDMI and card reader", "39.50", 8),
                product("27\" Monitor", "MON-004", "27 inch 1440p IPS monitor", "279.00", 4),
                product("Laptop Stand", "LS-005", "Adjustable aluminium laptop stand", "29.99", 0)
        );
        // Through the service, so each product's starting stock is recorded in the stock ledger.
        products.forEach(productService::createProduct);
        log.info("Seeded {} sample products", products.size());
    }

    private static Product product(String name, String sku, String description, String price, int quantity) {
        Product product = new Product();
        product.setName(name);
        product.setSku(sku);
        product.setDescription(description);
        product.setPrice(new BigDecimal(price));
        product.setQuantity(quantity);
        return product;
    }
}
