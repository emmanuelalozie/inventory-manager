package com.example.inventix.service;

import com.example.inventix.exception.ProductNotFoundException;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.StockMovement;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.repository.StockMovementRepository;
import com.example.inventix.service.impl.StockMovementServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockMovementServiceTest {

    @Mock
    private StockMovementRepository stockMovementRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private StockMovementServiceImpl stockMovementService;

    @Test
    void getMovements_passesFiltersAndPage() {
        StockMovement movement = new StockMovement();
        when(stockMovementRepository.search(1L, MovementReason.SALE, PageRequest.of(2, 25))).thenReturn(List.of(movement));

        assertThat(stockMovementService.getMovements(1L, MovementReason.SALE, 2, 25)).containsExactly(movement);
    }

    @Test
    void getMovements_rejectsBadPaging() {
        assertThatThrownBy(() -> stockMovementService.getMovements(null, null, -1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stockMovementService.getMovements(null, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stockMovementService.getMovements(null, null, 0, StockMovementService.MAX_PAGE_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be between 1 and 500");
        verify(stockMovementRepository, never()).search(any(), any(), any());
    }

    @Test
    void getMovementsForProduct_throwsNotFound_whenProductIsMissing() {
        when(productRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> stockMovementService.getMovementsForProduct(99L, 0, 10))
                .isInstanceOf(ProductNotFoundException.class);
        verify(stockMovementRepository, never()).search(any(), any(), any());
    }

    @Test
    void getMovementsForProduct_filtersByProductOnly() {
        when(productRepository.existsById(1L)).thenReturn(true);
        when(stockMovementRepository.search(1L, null, PageRequest.of(0, 10))).thenReturn(List.of());

        assertThat(stockMovementService.getMovementsForProduct(1L, 0, 10)).isEmpty();
    }
}
