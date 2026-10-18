package com.example.inventix.repository;

import com.example.inventix.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, Long id);

    boolean existsBySupplierId(Long supplierId);

    boolean existsByCategoryId(Long categoryId);

    @Query("select p.categoryId, count(p) from Product p where p.categoryId is not null group by p.categoryId")
    List<Object[]> countByCategory();

    /**
     * Per category (null for uncategorised): products, units, value at price, value at unit cost (products without a
     * cost count as 0), and how many products have no unit cost.
     */
    @Query("select p.categoryId, count(p), coalesce(sum(p.quantity), 0),"
            + " coalesce(sum(p.price * p.quantity), 0),"
            + " coalesce(sum(case when p.unitCost is null then 0 else p.unitCost * p.quantity end), 0),"
            + " sum(case when p.unitCost is null then 1 else 0 end)"
            + " from Product p group by p.categoryId")
    List<Object[]> stockValueByCategory();

    @Query("select count(l) > 0 from PurchaseOrderLine l where l.product.id = :productId")
    boolean isOnPurchaseOrder(@Param("productId") Long productId);

    @Query("select count(r) > 0 from ProductionRun r where r.product.id = :productId")
    boolean isOnProductionRun(@Param("productId") Long productId);

    @Query("select count(c) > 0 from ProductComponent c where c.component.id = :productId")
    boolean isUsedAsComponent(@Param("productId") Long productId);

    @Query("select count(c) > 0 from ProductComponent c where c.productId = :productId")
    boolean hasComponents(@Param("productId") Long productId);

    @Modifying
    @Query("delete from ProductComponent c where c.productId = :productId")
    void deleteComponentsOf(@Param("productId") Long productId);

    List<Product> findByQuantityLessThanEqualOrderByQuantityAsc(Integer threshold);

    /** Products with a reorder point whose stock is at or below it, lowest stock first. */
    @Query("select p from Product p where p.reorderPoint is not null and p.quantity <= p.reorderPoint"
            + " order by p.quantity asc, p.name asc")
    List<Product> findAtOrBelowReorderPoint();

    /**
     * Products whose name, SKU or description contains the pattern (a lower-case LIKE pattern with '\' as the
     * escape character), or all products when it is null; optionally only those in one category.
     */
    @Query(value = "select p from Product p where (:categoryId is null or p.categoryId = :categoryId) and (:pattern is null"
            + " or lower(p.name) like :pattern escape '\\'"
            + " or lower(p.sku) like :pattern escape '\\'"
            + " or lower(p.description) like :pattern escape '\\')",
            countQuery = "select count(p) from Product p where (:categoryId is null or p.categoryId = :categoryId) and (:pattern is null"
            + " or lower(p.name) like :pattern escape '\\'"
            + " or lower(p.sku) like :pattern escape '\\'"
            + " or lower(p.description) like :pattern escape '\\')")
    Page<Product> search(@Param("pattern") String pattern, @Param("categoryId") Long categoryId, Pageable pageable);
}
