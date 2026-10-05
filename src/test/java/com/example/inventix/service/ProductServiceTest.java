package com.example.inventix.service;

import com.example.inventix.exception.DuplicateSkuException;
import com.example.inventix.exception.InsufficientStockException;
import com.example.inventix.exception.ProductInUseException;
import com.example.inventix.exception.ProductNotFoundException;
import com.example.inventix.model.Product;
import com.example.inventix.repository.OrderItemRepository;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.Mockito.*;

class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @InjectMocks
    private ProductServiceImpl productService;

    private Product product;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        product = new Product();
        product.setId(1L);
        product.setName("Sample Product");
        product.setSku("SKU12345");
        product.setDescription("A sample product for testing");
        product.setPrice(BigDecimal.valueOf(99.99));
        product.setQuantity(100);
    }

    @Test
    void testCreateProduct() {
        when(productRepository.save(product)).thenReturn(product);
        Product createdProduct = productService.createProduct(product);
        assertThat(createdProduct).isNotNull();
        verify(productRepository, times(1)).save(product);
    }

    @Test
    void createProduct_ignoresClientSuppliedId() {
        when(productRepository.save(any(Product.class))).thenAnswer(returnsFirstArg());

        productService.createProduct(product);

        // An id in the request must never overwrite an existing product.
        assertThat(product.getId()).isNull();
    }

    @Test
    void createProduct_throwsDuplicateSku_whenSkuExists() {
        when(productRepository.existsBySku("SKU12345")).thenReturn(true);

        assertThrows(DuplicateSkuException.class, () -> productService.createProduct(product));
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void testGetProductById_NotFound() {
        when(productRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(ProductNotFoundException.class, () -> productService.getProductById(1L));
    }

    @Test
    void updateProduct_copiesEditableFields() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.existsBySkuAndIdNot("NEW-SKU", 1L)).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(returnsFirstArg());
        Product changes = new Product(99L, "Renamed", "NEW-SKU", "New description", new BigDecimal("10.50"), 7, null, null);

        Product updated = productService.updateProduct(1L, changes);

        assertThat(updated.getId()).isEqualTo(1L);
        assertThat(updated.getName()).isEqualTo("Renamed");
        assertThat(updated.getSku()).isEqualTo("NEW-SKU");
        assertThat(updated.getDescription()).isEqualTo("New description");
        assertThat(updated.getPrice()).isEqualByComparingTo("10.50");
        assertThat(updated.getQuantity()).isEqualTo(7);
    }

    @Test
    void updateProduct_throwsDuplicateSku_whenSkuBelongsToAnotherProduct() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.existsBySkuAndIdNot("TAKEN", 1L)).thenReturn(true);
        Product changes = new Product(null, "Renamed", "TAKEN", null, BigDecimal.ONE, 1, null, null);

        assertThrows(DuplicateSkuException.class, () -> productService.updateProduct(1L, changes));
        verify(productRepository, never()).save(any(Product.class));
        assertThat(product.getSku()).isEqualTo("SKU12345");
    }

    @Test
    void updateProduct_throwsNotFound_whenProductIsMissing() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () -> productService.updateProduct(99L, product));
    }

    @Test
    void adjustStock_decrementsQuantity() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(returnsFirstArg());

        Product result = productService.adjustStock(1L, -30);

        assertThat(result.getQuantity()).isEqualTo(70);
        verify(productRepository).save(product);
    }

    @Test
    void adjustStock_restocksQuantity() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(returnsFirstArg());

        Product result = productService.adjustStock(1L, 25);

        assertThat(result.getQuantity()).isEqualTo(125);
    }

    @Test
    void adjustStock_allowsTakingTheLastUnit() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(returnsFirstArg());

        Product result = productService.adjustStock(1L, -100);

        assertThat(result.getQuantity()).isZero();
    }

    @Test
    void adjustStock_throwsInsufficientStock_whenStockWouldGoNegative() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        assertThrows(InsufficientStockException.class, () -> productService.adjustStock(1L, -101));
        assertThat(product.getQuantity()).isEqualTo(100);
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void adjustStock_throwsNotFound_whenProductIsMissing() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () -> productService.adjustStock(99L, -1));
    }

    @Test
    void getLowStockProducts_queriesByThreshold() {
        when(productRepository.findByQuantityLessThanEqualOrderByQuantityAsc(10)).thenReturn(List.of(product));

        assertThat(productService.getLowStockProducts(10)).containsExactly(product);
    }

    @Test
    void deleteProduct_deletesUnusedProduct() {
        when(productRepository.existsById(1L)).thenReturn(true);
        when(orderItemRepository.existsByProductId(1L)).thenReturn(false);

        productService.deleteProduct(1L);

        verify(productRepository).deleteById(1L);
    }

    @Test
    void deleteProduct_throwsNotFound_whenProductIsMissing() {
        when(productRepository.existsById(99L)).thenReturn(false);

        assertThrows(ProductNotFoundException.class, () -> productService.deleteProduct(99L));
        verify(productRepository, never()).deleteById(anyLong());
    }

    @Test
    void deleteProduct_throwsProductInUse_whenProductIsOnAnOrder() {
        when(productRepository.existsById(1L)).thenReturn(true);
        when(orderItemRepository.existsByProductId(1L)).thenReturn(true);

        assertThrows(ProductInUseException.class, () -> productService.deleteProduct(1L));
        verify(productRepository, never()).deleteById(anyLong());
    }
}
