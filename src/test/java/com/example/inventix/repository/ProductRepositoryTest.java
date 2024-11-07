package com.example.inventix.repository;

import com.example.inventix.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ProductRepositoryTest {

    @Autowired
    private ProductRepository productRepository;

    private Product product;

    @BeforeEach
    void setUp() {
        product = new Product();
        product.setName("Sample Product");
        product.setSku("SKU12345");
        product.setDescription("A sample product for testing");
        product.setPrice(BigDecimal.valueOf(99.99));
        product.setQuantity(100);
    }

    @Test
    void testSaveProduct() {
        Product savedProduct = productRepository.save(product);
        assertThat(savedProduct.getId()).isNotNull();
        assertThat(savedProduct.getName()).isEqualTo("Sample Product");
    }

    @Test
    void testFindById() {
        Product savedProduct = productRepository.save(product);
        Optional<Product> foundProduct = productRepository.findById(savedProduct.getId());

        assertThat(foundProduct).isPresent();
        assertThat(foundProduct.get().getName()).isEqualTo("Sample Product");
    }

    @Test
    void testUpdateProduct() {
        Product savedProduct = productRepository.save(product);
        savedProduct.setName("Updated Product");
        Product updatedProduct = productRepository.save(savedProduct);
        assertThat(updatedProduct.getName()).isEqualTo("Updated Product");
    }

    @Test
    void testDeleteProduct() {
        Product savedProduct = productRepository.save(product);
        productRepository.deleteById(savedProduct.getId());
        boolean exists = productRepository.findById(savedProduct.getId()).isPresent();
        assertThat(exists).isFalse();
    }
}
