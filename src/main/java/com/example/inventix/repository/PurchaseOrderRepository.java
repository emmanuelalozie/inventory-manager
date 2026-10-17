package com.example.inventix.repository;

import com.example.inventix.model.PurchaseOrder;
import com.example.inventix.model.PurchaseOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

// Purchase orders are loaded with their supplier, lines and products, because responses are built after the
// transaction (open-in-view is off). Lists page the ids first, like OrderRepository.
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    @Override
    @EntityGraph(attributePaths = {"supplier", "lines", "lines.product"})
    Optional<PurchaseOrder> findById(Long id);

    @Query(value = "select po.id from PurchaseOrder po"
            + " where (:status is null or po.status = :status) and (:supplierId is null or po.supplier.id = :supplierId)",
            countQuery = "select count(po) from PurchaseOrder po"
            + " where (:status is null or po.status = :status) and (:supplierId is null or po.supplier.id = :supplierId)")
    Page<Long> findIds(@Param("status") PurchaseOrderStatus status, @Param("supplierId") Long supplierId,
                       Pageable pageable);

    @EntityGraph(attributePaths = {"supplier", "lines", "lines.product"})
    List<PurchaseOrder> findByIdIn(Collection<Long> ids);

    /** The newest draft for a supplier, which Restock adds to instead of starting another draft. */
    @EntityGraph(attributePaths = {"supplier", "lines", "lines.product"})
    Optional<PurchaseOrder> findFirstBySupplierIdAndStatusOrderByIdDesc(Long supplierId, PurchaseOrderStatus status);

    boolean existsBySupplierId(Long supplierId);

    /** Units still to come (ordered, not received or cancelled), per product, over open purchase orders. */
    @Query("select l.product.id, sum(l.quantityOrdered - l.quantityReceived - l.quantityCancelled) from PurchaseOrderLine l"
            + " where l.purchaseOrder.status in :statuses and l.product.id in :productIds group by l.product.id")
    List<Object[]> sumOpenQuantities(@Param("statuses") Collection<PurchaseOrderStatus> statuses,
                                     @Param("productIds") Collection<Long> productIds);
}
