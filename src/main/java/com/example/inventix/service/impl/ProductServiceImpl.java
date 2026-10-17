package com.example.inventix.service.impl;

import com.example.inventix.exception.BadRequestException;
import com.example.inventix.exception.DuplicateSkuException;
import com.example.inventix.exception.InsufficientStockException;
import com.example.inventix.exception.ProductInUseException;
import com.example.inventix.exception.ProductNotFoundException;
import com.example.inventix.exception.QuantityChangeNotAllowedException;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Product;
import com.example.inventix.model.ProductSource;
import com.example.inventix.model.StockMovement;
import com.example.inventix.repository.CategoryRepository;
import com.example.inventix.repository.OrderItemRepository;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.repository.SupplierRepository;
import com.example.inventix.repository.StockMovementRepository;
import com.example.inventix.service.Paging;
import com.example.inventix.service.ProductService;
import com.example.inventix.service.StockReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@Transactional
public class ProductServiceImpl implements ProductService {

    static final String INITIAL_STOCK_NOTE = "Initial stock";

    static final Set<String> SORT_FIELDS = Set.of("id", "name", "sku", "price", "unitCost", "quantity", "createdAt",
            "updatedAt");

    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockMovementRepository stockMovementRepository;
    private final SupplierRepository supplierRepository;
    private final CategoryRepository categoryRepository;

    @Autowired
    public ProductServiceImpl(ProductRepository productRepository, OrderItemRepository orderItemRepository,
                              StockMovementRepository stockMovementRepository, SupplierRepository supplierRepository,
                              CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.orderItemRepository = orderItemRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.supplierRepository = supplierRepository;
        this.categoryRepository = categoryRepository;
    }

    @Override
    public Product createProduct(Product product) {
        product.setId(null); // always insert, never overwrite an existing product
        product.setVersion(null); // a client-sent version would make Spring Data treat the product as existing
        product.setSku(normalizeSku(product.getSku()));
        checkSourcing(product);
        if (productRepository.existsBySku(product.getSku())) {
            throw new DuplicateSkuException("A product with SKU '" + product.getSku() + "' already exists");
        }
        int initialQuantity = product.getQuantity() == null ? 0 : product.getQuantity();
        product.setQuantity(initialQuantity);
        Product saved = productRepository.save(product);
        // The starting stock is the product's first ledger entry, so its movements add up to its quantity.
        if (initialQuantity != 0) {
            recordMovement(saved, initialQuantity, MovementReason.ADJUSTMENT, INITIAL_STOCK_NOTE, StockReference.NONE);
        }
        return saved;
    }

    @Override
    public Product updateProduct(Long id, Product productDetails) {
        Product existingProduct = getProductById(id);

        // The version is optional in the request. If sent, it must match what is stored, so an edit based on
        // stale data can't silently overwrite someone else's change. JPA still manages the version itself.
        if (productDetails.getVersion() != null && !productDetails.getVersion().equals(existingProduct.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Product.class, id);
        }

        // Stock must not change without a ledger entry, so the quantity can't be edited here.
        if (productDetails.getQuantity() != null && !productDetails.getQuantity().equals(existingProduct.getQuantity())) {
            throw new QuantityChangeNotAllowedException("Quantity can't be changed by editing the product (stored "
                    + existingProduct.getQuantity() + ", sent " + productDetails.getQuantity()
                    + "). Use PATCH /api/products/" + id + "/stock with a note, or leave quantity out.");
        }

        productDetails.setSku(normalizeSku(productDetails.getSku()));
        checkSourcing(productDetails);
        if (productDetails.getSource() != ProductSource.MAKE && productRepository.hasComponents(id)) {
            throw new BadRequestException("This product has components, so it must stay made in-house (MAKE). "
                    + "Remove its components first.");
        }
        if (productRepository.existsBySkuAndIdNot(productDetails.getSku(), id)) {
            throw new DuplicateSkuException("A product with SKU '" + productDetails.getSku() + "' already exists");
        }

        existingProduct.setName(productDetails.getName());
        existingProduct.setSku(productDetails.getSku());
        existingProduct.setDescription(productDetails.getDescription());
        existingProduct.setPrice(productDetails.getPrice());
        existingProduct.setSource(productDetails.getSource());
        existingProduct.setSupplierId(productDetails.getSupplierId());
        existingProduct.setReorderPoint(productDetails.getReorderPoint());
        existingProduct.setReorderQuantity(productDetails.getReorderQuantity());
        existingProduct.setCategoryId(productDetails.getCategoryId());
        existingProduct.setUnitCost(productDetails.getUnitCost());

        return productRepository.save(existingProduct);
    }

    @Override
    public Product getProductById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + id));
    }

    @Override
    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    @Override
    public Page<Product> searchProducts(String query, Long categoryId, Integer page, Integer size, String sort) {
        String pattern = query == null || query.isBlank() ? null : "%" + escapeLike(query.trim().toLowerCase(Locale.ROOT)) + "%";
        return productRepository.search(pattern, categoryId,
                Paging.pageable(page, size, Paging.sort(sort, SORT_FIELDS, "id")));
    }

    @Override
    public List<Product> getLowStockProducts(int threshold) {
        if (threshold < 0) {
            throw new BadRequestException("threshold must be zero or greater");
        }
        return productRepository.findByQuantityLessThanEqualOrderByQuantityAsc(threshold);
    }

    @Override
    public Product changeStock(Long productId, int delta, MovementReason reason, String note,
                               StockReference reference) {
        if (reason == null) {
            throw new BadRequestException("reason is required");
        }
        if (delta == 0) {
            throw new BadRequestException("delta must not be 0");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (reason == MovementReason.ADJUSTMENT && cleanNote == null) {
            throw new BadRequestException("A note is required for manual stock adjustments");
        }

        Product product = getProductById(productId);
        int available = product.getQuantity() == null ? 0 : product.getQuantity();
        long newQuantity = (long) available + delta; // long so a large delta can't wrap around
        if (newQuantity < 0) {
            throw new InsufficientStockException("Not enough stock for product '" + product.getName()
                    + "': available " + available + ", requested " + (-(long) delta));
        }
        if (newQuantity > Integer.MAX_VALUE) {
            throw new BadRequestException("Stock for product '" + product.getName()
                    + "' cannot exceed " + Integer.MAX_VALUE);
        }
        product.setQuantity((int) newQuantity);
        Product saved = productRepository.save(product);
        recordMovement(saved, delta, reason, cleanNote, reference == null ? StockReference.NONE : reference);
        return saved;
    }

    @Override
    public void deleteProduct(Long id) {
        if (!productRepository.existsById(id)) {
            throw new ProductNotFoundException("Product not found with id: " + id);
        }
        if (orderItemRepository.existsByProductId(id)) {
            throw new ProductInUseException("Product " + id + " is used by existing orders and cannot be deleted");
        }
        if (productRepository.isOnPurchaseOrder(id)) {
            throw new ProductInUseException("Product " + id + " is on purchase orders and cannot be deleted");
        }
        if (productRepository.isOnProductionRun(id)) {
            throw new ProductInUseException("Product " + id + " has production runs and cannot be deleted");
        }
        if (productRepository.isUsedAsComponent(id)) {
            throw new ProductInUseException("Product " + id + " is a component of other products and cannot be deleted");
        }
        productRepository.deleteComponentsOf(id);
        // The ledger rows point at the product (foreign key), so its history goes with it.
        stockMovementRepository.deleteByProductId(id);
        productRepository.deleteById(id);
    }

    private void recordMovement(Product product, int delta, MovementReason reason, String note,
                                StockReference reference) {
        StockMovement movement = new StockMovement(product, delta, reason, note, reference.orderId());
        movement.setPurchaseOrderId(reference.purchaseOrderId());
        movement.setProductionRunId(reference.productionRunId());
        stockMovementRepository.save(movement);
    }

    // SKUs are compared and stored trimmed and upper-case, so "abc-1 " and "ABC-1" can't both exist.
    private static String normalizeSku(String sku) {
        return sku == null ? null : sku.trim().toUpperCase(Locale.ROOT);
    }

    // Makes % and _ in a search term match literally (the queries use '\' as the LIKE escape character).
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // Made products have no supplier, and a supplier and category must exist. A missing source means BUY.
    private void checkSourcing(Product product) {
        if (product.getCategoryId() != null && !categoryRepository.existsById(product.getCategoryId())) {
            throw new BadRequestException("Category " + product.getCategoryId() + " doesn't exist");
        }
        if (product.getSource() == null) {
            product.setSource(ProductSource.BUY);
        }
        Long supplierId = product.getSupplierId();
        if (supplierId == null) {
            return;
        }
        if (product.getSource() == ProductSource.MAKE) {
            throw new BadRequestException("Products made in-house have no supplier; leave supplierId out");
        }
        if (!supplierRepository.existsById(supplierId)) {
            throw new BadRequestException("Supplier " + supplierId + " doesn't exist");
        }
    }
}
