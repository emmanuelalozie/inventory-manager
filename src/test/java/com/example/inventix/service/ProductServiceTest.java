package com.example.inventix.service;

import com.example.inventix.exception.DuplicateSkuException;
import com.example.inventix.exception.InsufficientStockException;
import com.example.inventix.exception.ProductInUseException;
import com.example.inventix.exception.ProductNotFoundException;
import com.example.inventix.exception.QuantityChangeNotAllowedException;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Product;
import com.example.inventix.model.StockMovement;
import com.example.inventix.repository.OrderItemRepository;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.repository.StockMovementRepository;
import com.example.inventix.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.Mockito.*;

class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

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

    // The single movement written during the test.
    private StockMovement savedMovement() {
        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void testCreateProduct() {
        when(productRepository.save(product)).thenReturn(product);
        Product createdProduct = productService.createProduct(product);
        assertThat(createdProduct).isNotNull();
        verify(productRepository, times(1)).save(product);
    }

    @Test
    void createProduct_recordsInitialStockAsAdjustment() {
        when(productRepository.save(any(Product.class))).thenAnswer(returnsFirstArg());

        productService.createProduct(product);

        StockMovement movement = savedMovement();
        assertThat(movement.getProduct()).isSameAs(product);
        assertThat(movement.getDelta()).isEqualTo(100);
        assertThat(movement.getReason()).isEqualTo(MovementReason.ADJUSTMENT);
        assertThat(movement.getNote()).isEqualTo("Initial stock");
        assertThat(movement.getOrderId()).isNull();
    }

    @Test
    void createProduct_withZeroQuantity_writesNoMovement() {
        product.setQuantity(0);
        when(productRepository.save(any(Product.class))).thenAnswer(returnsFirstArg());

        productService.createProduct(product);

        verify(stockMovementRepository, never()).save(any(StockMovement.class));
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
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
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
        Product changes = new Product(99L, "Renamed", "NEW-SKU", "New description", new BigDecimal("10.50"), 100, null, null);

        Product updated = productService.updateProduct(1L, changes);

        assertThat(updated.getId()).isEqualTo(1L);
        assertThat(updated.getName()).isEqualTo("Renamed");
        assertThat(updated.getSku()).isEqualTo("NEW-SKU");
        assertThat(updated.getDescription()).isEqualTo("New description");
        assertThat(updated.getPrice()).isEqualByComparingTo("10.50");
        assertThat(updated.getQuantity()).isEqualTo(100);
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
    }

    @Test
    void updateProduct_withoutQuantity_keepsStoredQuantity() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(returnsFirstArg());
        Product changes = new Product(null, "Renamed", "SKU12345", null, BigDecimal.ONE, null, null, null);

        Product updated = productService.updateProduct(1L, changes);

        assertThat(updated.getName()).isEqualTo("Renamed");
        assertThat(updated.getQuantity()).isEqualTo(100);
    }

    @Test
    void updateProduct_rejectsQuantityChange_soStockNeverChangesWithoutAMovement() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        Product changes = new Product(null, "Renamed", "SKU12345", null, BigDecimal.ONE, 7, null, null);

        assertThatThrownBy(() -> productService.updateProduct(1L, changes))
                .isInstanceOf(QuantityChangeNotAllowedException.class)
                .hasMessageContaining("/api/products/1/stock");

        assertThat(product.getQuantity()).isEqualTo(100);
        assertThat(product.getName()).isEqualTo("Sample Product");
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void updateProduct_throwsDuplicateSku_whenSkuBelongsToAnotherProduct() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.existsBySkuAndIdNot("TAKEN", 1L)).thenReturn(true);
        Product changes = new Product(null, "Renamed", "TAKEN", null, BigDecimal.ONE, null, null, null);

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
    void adjustStock_decrementsQuantityAndRecordsMovement() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(returnsFirstArg());

        Product result = productService.adjustStock(1L, -30, MovementReason.SALE, "Added to order #4", 4L);

        assertThat(result.getQuantity()).isEqualTo(70);
        verify(productRepository).save(product);
        StockMovement movement = savedMovement();
        assertThat(movement.getProduct()).isSameAs(product);
        assertThat(movement.getDelta()).isEqualTo(-30);
        assertThat(movement.getReason()).isEqualTo(MovementReason.SALE);
        assertThat(movement.getNote()).isEqualTo("Added to order #4");
        assertThat(movement.getOrderId()).isEqualTo(4L);
        assertThat(movement.getPurchaseOrderId()).isNull();
        assertThat(movement.getLocationId()).isNull();
    }

    @Test
    void adjustStock_manualAdjustment_recordsTrimmedNote() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(returnsFirstArg());

        Product result = productService.adjustStock(1L, 25, MovementReason.ADJUSTMENT, "  Found in back room ", null);

        assertThat(result.getQuantity()).isEqualTo(125);
        StockMovement movement = savedMovement();
        assertThat(movement.getReason()).isEqualTo(MovementReason.ADJUSTMENT);
        assertThat(movement.getDelta()).isEqualTo(25);
        assertThat(movement.getNote()).isEqualTo("Found in back room");
        assertThat(movement.getOrderId()).isNull();
    }

    @Test
    void adjustStock_manualAdjustment_requiresANote() {
        assertThatThrownBy(() -> productService.adjustStock(1L, 5, MovementReason.ADJUSTMENT, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("note is required");
        assertThatThrownBy(() -> productService.adjustStock(1L, 5, MovementReason.ADJUSTMENT, "   ", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(product.getQuantity()).isEqualTo(100);
        verify(productRepository, never()).save(any(Product.class));
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
    }

    @Test
    void adjustStock_orderMovement_doesNotNeedANote() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(returnsFirstArg());

        productService.adjustStock(1L, 2, MovementReason.CANCEL, null, 3L);

        assertThat(savedMovement().getNote()).isNull();
    }

    @Test
    void adjustStock_rejectsZeroDelta() {
        assertThatThrownBy(() -> productService.adjustStock(1L, 0, MovementReason.ADJUSTMENT, "Nothing", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delta must not be 0");
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
    }

    @Test
    void adjustStock_rejectsMissingReason() {
        assertThrows(IllegalArgumentException.class, () -> productService.adjustStock(1L, 1, null, "x", null));
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
    }

    @Test
    void adjustStock_allowsTakingTheLastUnit() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(returnsFirstArg());

        Product result = productService.adjustStock(1L, -100, MovementReason.SALE, null, 1L);

        assertThat(result.getQuantity()).isZero();
    }

    @Test
    void adjustStock_throwsInsufficientStock_whenStockWouldGoNegative() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        assertThrows(InsufficientStockException.class,
                () -> productService.adjustStock(1L, -101, MovementReason.SALE, null, 1L));
        assertThat(product.getQuantity()).isEqualTo(100);
        verify(productRepository, never()).save(any(Product.class));
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
    }

    @Test
    void adjustStock_throwsNotFound_whenProductIsMissing() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class,
                () -> productService.adjustStock(99L, -1, MovementReason.ADJUSTMENT, "Damaged", null));
    }

    @Test
    void getLowStockProducts_queriesByThreshold() {
        when(productRepository.findByQuantityLessThanEqualOrderByQuantityAsc(10)).thenReturn(List.of(product));

        assertThat(productService.getLowStockProducts(10)).containsExactly(product);
    }

    @Test
    void deleteProduct_deletesUnusedProductAndItsMovements() {
        when(productRepository.existsById(1L)).thenReturn(true);
        when(orderItemRepository.existsByProductId(1L)).thenReturn(false);

        productService.deleteProduct(1L);

        var sequence = inOrder(stockMovementRepository, productRepository);
        sequence.verify(stockMovementRepository).deleteByProductId(1L);
        sequence.verify(productRepository).deleteById(1L);
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
        verify(stockMovementRepository, never()).deleteByProductId(anyLong());
    }
}
