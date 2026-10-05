package com.example.inventix.service;

import com.example.inventix.model.Product;
import com.example.inventix.repository.OrderItemRepository;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.repository.StockMovementRepository;
import com.example.inventix.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Optimistic locking rules in ProductServiceImpl (O-8): a client-sent version is checked on update
 * and never copied onto the stored product, and create always starts a new version.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceVersionTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @InjectMocks
    private ProductServiceImpl productService;

    private Product existing;

    @BeforeEach
    void setUp() {
        existing = new Product(1L, "Mouse", "WM-001", null, new BigDecimal("24.99"), 10, null, null);
        existing.setVersion(2L);
    }

    private static Product changes(Long version) {
        // Same quantity as stored: stock can't be changed through an update.
        Product changes = new Product(null, "Mouse v2", "WM-001", "Updated", new BigDecimal("25.99"), 10, null, null);
        changes.setVersion(version);
        return changes;
    }

    @Test
    void updateWithStaleVersionIsRejectedAndNothingIsSaved() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> productService.updateProduct(1L, changes(1L)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(productRepository, never()).save(any(Product.class));
        assertThat(existing.getName()).isEqualTo("Mouse");
    }

    @Test
    void updateWithMatchingVersionSavesAndKeepsStoredVersion() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product updated = productService.updateProduct(1L, changes(2L));

        assertThat(updated.getName()).isEqualTo("Mouse v2");
        assertThat(updated.getVersion()).isEqualTo(2L); // JPA increments it on flush, the service never sets it
    }

    @Test
    void updateWithoutVersionIsAllowed() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product updated = productService.updateProduct(1L, changes(null));

        assertThat(updated.getPrice()).isEqualByComparingTo("25.99");
        assertThat(updated.getVersion()).isEqualTo(2L);
    }

    @Test
    void createClearsClientSentIdAndVersion() {
        Product incoming = changes(7L);
        incoming.setId(99L);
        when(productRepository.existsBySku("WM-001")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product created = productService.createProduct(incoming);

        assertThat(created.getId()).isNull();
        assertThat(created.getVersion()).isNull();
    }
}
