package com.example.inventix.service.impl;

import com.example.inventix.exception.ConflictException;
import com.example.inventix.model.Product;
import com.example.inventix.model.ProductSource;
import com.example.inventix.model.PurchaseOrderStatus;
import com.example.inventix.model.Supplier;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.repository.ProductionRunRepository;
import com.example.inventix.repository.PurchaseOrderRepository;
import com.example.inventix.repository.SupplierRepository;
import com.example.inventix.service.ProductService;
import com.example.inventix.service.ProductionRunService;
import com.example.inventix.service.PurchaseOrderService;
import com.example.inventix.service.RestockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class RestockServiceImpl implements RestockService {

    static final List<PurchaseOrderStatus> OPEN_STATUSES = List.of(
            PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.ORDERED, PurchaseOrderStatus.PARTIALLY_RECEIVED);

    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ProductionRunRepository productionRunRepository;
    private final ProductService productService;
    private final PurchaseOrderService purchaseOrderService;
    private final ProductionRunService productionRunService;

    @Autowired
    public RestockServiceImpl(ProductRepository productRepository, SupplierRepository supplierRepository,
                              PurchaseOrderRepository purchaseOrderRepository,
                              ProductionRunRepository productionRunRepository, ProductService productService,
                              PurchaseOrderService purchaseOrderService, ProductionRunService productionRunService) {
        this.productRepository = productRepository;
        this.supplierRepository = supplierRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.productionRunRepository = productionRunRepository;
        this.productService = productService;
        this.purchaseOrderService = purchaseOrderService;
        this.productionRunService = productionRunService;
    }

    @Override
    public List<ReorderItem> getReorderList() {
        List<Product> products = productRepository.findAtOrBelowReorderPoint();
        if (products.isEmpty()) {
            return List.of();
        }
        List<Long> ids = products.stream().map(Product::getId).toList();
        Map<Long, Long> onOrder = new HashMap<>();
        purchaseOrderRepository.sumOpenQuantities(OPEN_STATUSES, ids)
                .forEach(row -> onOrder.merge((Long) row[0], ((Number) row[1]).longValue(), Long::sum));
        productionRunRepository.sumPlannedQuantities(ids)
                .forEach(row -> onOrder.merge((Long) row[0], ((Number) row[1]).longValue(), Long::sum));
        Map<Long, String> supplierNames = supplierRepository.findAllById(
                        products.stream().map(Product::getSupplierId).filter(id -> id != null).distinct().toList())
                .stream().collect(Collectors.toMap(Supplier::getId, Supplier::getName));

        return products.stream().map(product -> {
            long coming = onOrder.getOrDefault(product.getId(), 0L);
            return new ReorderItem(product.getId(), product.getName(), product.getSku(), product.getSource(),
                    product.getSupplierId(), supplierNames.get(product.getSupplierId()), product.getQuantity(),
                    product.getReorderPoint(), product.getReorderQuantity(), coming,
                    product.getQuantity() + coming > product.getReorderPoint(), problem(product));
        }).toList();
    }

    @Override
    public RestockResult restock(Long productId) {
        Product product = productService.getProductById(productId);
        String problem = problem(product);
        if (problem != null) {
            throw new ConflictException("Can't restock " + product.getSku() + ": " + problem);
        }
        return replenish(product);
    }

    @Override
    public List<RestockResult> restockAll() {
        Map<Long, Product> products = productRepository.findAtOrBelowReorderPoint().stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return getReorderList().stream().map(item -> {
            if (item.problem() != null) {
                return skipped(item, item.problem());
            }
            if (item.covered()) {
                return skipped(item, item.onOrder() + " already on order or planned");
            }
            return replenish(products.get(item.productId()));
        }).toList();
    }

    private RestockResult replenish(Product product) {
        int quantity = product.getReorderQuantity();
        if (product.getSource() == ProductSource.MAKE) {
            ProductionRunService.PlannedRun planned = productionRunService.planIfNone(product.getId(), quantity);
            Long runId = planned.run().getId();
            return new RestockResult(product.getId(), product.getSku(), "PRODUCTION_RUN", null, runId,
                    planned.created(), planned.created(), planned.created()
                    ? "Planned production run #" + runId + " for " + quantity
                    : "Already planned in production run #" + runId);
        }
        PurchaseOrderService.DraftAddition draft = purchaseOrderService.addToDraft(
                product.getSupplierId(), product.getId(), quantity);
        Long poId = draft.purchaseOrder().getId();
        return new RestockResult(product.getId(), product.getSku(), "PURCHASE_ORDER", poId, null, draft.created(),
                draft.lineAdded(), draft.lineAdded()
                ? "Added " + quantity + " to draft purchase order #" + poId
                : "Already on draft purchase order #" + poId);
    }

    private static RestockResult skipped(ReorderItem item, String reason) {
        return new RestockResult(item.productId(), item.sku(), "SKIPPED", null, null, false, false, reason);
    }

    // What has to be set before Restock can act, or null when the product is ready.
    private static String problem(Product product) {
        if (product.getReorderQuantity() == null) {
            return "no reorder quantity set";
        }
        if (product.getSource() != ProductSource.MAKE && product.getSupplierId() == null) {
            return "no supplier set";
        }
        return null;
    }
}
