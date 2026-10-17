package com.example.inventix.service;

import com.example.inventix.exception.BadRequestException;
import com.example.inventix.exception.ConflictException;
import com.example.inventix.exception.NotFoundException;
import com.example.inventix.model.Product;
import com.example.inventix.model.ProductSource;
import com.example.inventix.model.Supplier;
import com.example.inventix.service.impl.ProductServiceImpl;
import com.example.inventix.service.impl.SupplierServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Suppliers and the sourcing fields on products, against H2 with the real migrations.
 */
@DataJpaTest
@Import({SupplierServiceImpl.class, ProductServiceImpl.class})
class SupplierSourcingIntegrationTest {

    @Autowired
    private SupplierService supplierService;

    @Autowired
    private ProductService productService;

    @Autowired
    private TestEntityManager entityManager;

    private static Supplier supplier(String name) {
        Supplier supplier = new Supplier();
        supplier.setName(name);
        return supplier;
    }

    private static Product product(String sku, ProductSource source, Long supplierId) {
        Product product = new Product(null, "Product " + sku, sku, null, new BigDecimal("5.00"), 10, null, null);
        product.setSource(source);
        product.setSupplierId(supplierId);
        return product;
    }

    @Test
    void suppliersAreListedByName_andNamesAreUniqueIgnoringCase() {
        supplierService.createSupplier(supplier("Zeta Parts"));
        supplierService.createSupplier(supplier("  Acme Supply "));

        assertThat(supplierService.getAllSuppliers()).extracting(Supplier::getName)
                .containsExactly("Acme Supply", "Zeta Parts");
        assertThatThrownBy(() -> supplierService.createSupplier(supplier("acme supply")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updateRejectsAStaleVersion_andMissingSuppliersAre404() {
        Supplier saved = supplierService.createSupplier(supplier("Acme"));
        entityManager.flush();
        Supplier stale = supplier("Acme Ltd");
        stale.setVersion(saved.getVersion() + 1);

        assertThatThrownBy(() -> supplierService.updateSupplier(saved.getId(), stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThatThrownBy(() -> supplierService.getSupplier(9999L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void productsKeepTheirSourcing_andASupplierInUseCannotBeDeleted() {
        Long acmeId = supplierService.createSupplier(supplier("Acme")).getId();
        Product created = product("BOLT-1", ProductSource.BUY, acmeId);
        created.setReorderPoint(5);
        created.setReorderQuantity(50);
        Long productId = productService.createProduct(created).getId();
        entityManager.flush();
        entityManager.clear();

        Product loaded = productService.getProductById(productId);
        assertThat(loaded.getSupplierId()).isEqualTo(acmeId);
        assertThat(loaded.getReorderPoint()).isEqualTo(5);
        assertThat(loaded.getReorderQuantity()).isEqualTo(50);

        assertThatThrownBy(() -> supplierService.deleteSupplier(acmeId)).isInstanceOf(ConflictException.class);

        // Switching the product to MAKE (without a supplier) frees the supplier.
        Product changes = product("BOLT-1", ProductSource.MAKE, null);
        changes.setQuantity(null);
        productService.updateProduct(productId, changes);
        entityManager.flush();
        supplierService.deleteSupplier(acmeId);
        entityManager.flush();
        entityManager.clear();

        Product updated = productService.getProductById(productId);
        assertThat(updated.getSource()).isEqualTo(ProductSource.MAKE);
        assertThat(updated.getReorderPoint()).isNull();
    }

    @Test
    void rejectsASupplierOnAMadeProduct_andUnknownSuppliers() {
        Long acmeId = supplierService.createSupplier(supplier("Acme")).getId();

        assertThatThrownBy(() -> productService.createProduct(product("GEAR-1", ProductSource.MAKE, acmeId)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> productService.createProduct(product("GEAR-2", ProductSource.BUY, 9999L)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void sourceDefaultsToBuy() {
        Product created = productService.createProduct(product("NUT-1", null, null));

        assertThat(created.getSource()).isEqualTo(ProductSource.BUY);
    }
}
