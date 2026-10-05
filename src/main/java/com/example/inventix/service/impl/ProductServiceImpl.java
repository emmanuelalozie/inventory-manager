package com.example.inventix.service.impl;

import com.example.inventix.exception.DuplicateSkuException;
import com.example.inventix.exception.InsufficientStockException;
import com.example.inventix.exception.ProductInUseException;
import com.example.inventix.exception.ProductNotFoundException;
import com.example.inventix.model.Product;
import com.example.inventix.repository.OrderItemRepository;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;

    @Autowired
    public ProductServiceImpl(ProductRepository productRepository, OrderItemRepository orderItemRepository) {
        this.productRepository = productRepository;
        this.orderItemRepository = orderItemRepository;
    }

    @Override
    public Product createProduct(Product product) {
        product.setId(null); // always insert, never overwrite an existing product
        product.setVersion(null); // a client-sent version would make Spring Data treat the product as existing
        if (productRepository.existsBySku(product.getSku())) {
            throw new DuplicateSkuException("A product with SKU '" + product.getSku() + "' already exists");
        }
        return productRepository.save(product);
    }

    @Override
    public Product updateProduct(Long id, Product productDetails) {
        Product existingProduct = getProductById(id);

        // The version is optional in the request. If sent, it must match what is stored, so an edit based on
        // stale data can't silently overwrite someone else's change. JPA still manages the version itself.
        if (productDetails.getVersion() != null && !productDetails.getVersion().equals(existingProduct.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Product.class, id);
        }

        if (productRepository.existsBySkuAndIdNot(productDetails.getSku(), id)) {
            throw new DuplicateSkuException("A product with SKU '" + productDetails.getSku() + "' already exists");
        }

        existingProduct.setName(productDetails.getName());
        existingProduct.setSku(productDetails.getSku());
        existingProduct.setDescription(productDetails.getDescription());
        existingProduct.setPrice(productDetails.getPrice());
        existingProduct.setQuantity(productDetails.getQuantity());

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
    public List<Product> getLowStockProducts(int threshold) {
        return productRepository.findByQuantityLessThanEqualOrderByQuantityAsc(threshold);
    }

    @Override
    public Product adjustStock(Long id, int delta) {
        Product product = getProductById(id);
        int available = product.getQuantity() == null ? 0 : product.getQuantity();
        int newQuantity = available + delta;
        if (newQuantity < 0) {
            throw new InsufficientStockException("Not enough stock for product '" + product.getName()
                    + "': available " + available + ", requested " + (-delta));
        }
        product.setQuantity(newQuantity);
        return productRepository.save(product);
    }

    @Override
    public void deleteProduct(Long id) {
        if (!productRepository.existsById(id)) {
            throw new ProductNotFoundException("Product not found with id: " + id);
        }
        if (orderItemRepository.existsByProductId(id)) {
            throw new ProductInUseException("Product " + id + " is used by existing orders and cannot be deleted");
        }
        productRepository.deleteById(id);
    }
}
