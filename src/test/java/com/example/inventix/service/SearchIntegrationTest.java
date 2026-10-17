package com.example.inventix.service;

import com.example.inventix.exception.BadRequestException;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.model.Product;
import com.example.inventix.service.impl.OrderItemServiceImpl;
import com.example.inventix.service.impl.OrderServiceImpl;
import com.example.inventix.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Product search and the paged product and order lists, against H2.
 */
@DataJpaTest
@Import({ProductServiceImpl.class, OrderItemServiceImpl.class, OrderServiceImpl.class})
class SearchIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private TestEntityManager entityManager;

    private Long mouseId;

    @BeforeEach
    void setUp() {
        mouseId = productService.createProduct(product("Wireless Mouse", "WM-001", "2.4 GHz optical", "24.99", 50)).getId();
        productService.createProduct(product("Mouse Pad", "MP-002", null, "9.99", 5));
        productService.createProduct(product("Keyboard", "KB-003", "Works with any mouse", "89.90", 0));
        productService.createProduct(product("100% Cotton Cloth", "CL_004", "Screen cleaning", "3.50", 20));
        entityManager.flush();
        entityManager.clear();
    }

    private static Product product(String name, String sku, String description, String price, int quantity) {
        return new Product(null, name, sku, description, new BigDecimal(price), quantity, null, null);
    }

    private static List<String> skus(Page<Product> page) {
        return page.getContent().stream().map(Product::getSku).toList();
    }

    @Test
    void searchMatchesNameSkuOrDescription_caseInsensitive() {
        assertThat(skus(productService.searchProducts("MOUSE", null, null, "sku")))
                .containsExactly("KB-003", "MP-002", "WM-001");
        assertThat(skus(productService.searchProducts("kb-0", null, null, null))).containsExactly("KB-003");
    }

    @Test
    void searchTreatsPercentAndUnderscoreLiterally() {
        assertThat(skus(productService.searchProducts("100%", null, null, null))).containsExactly("CL_004");
        assertThat(skus(productService.searchProducts("l_0", null, null, null))).containsExactly("CL_004");
    }

    @Test
    void withoutPageOrSize_returnsEverything() {
        Page<Product> all = productService.searchProducts(null, null, null, null);

        assertThat(all.getContent()).hasSize(4);
        assertThat(all.getTotalElements()).isEqualTo(4);
    }

    @Test
    void pagesAreSortedAndCarryTheTotal() {
        Page<Product> first = productService.searchProducts(null, 0, 3, "quantity,desc");
        Page<Product> second = productService.searchProducts(null, 1, 3, "quantity,desc");

        assertThat(skus(first)).containsExactly("WM-001", "CL_004", "MP-002");
        assertThat(skus(second)).containsExactly("KB-003");
        assertThat(first.getTotalElements()).isEqualTo(4);
    }

    @Test
    void rejectsUnknownSortFieldAndBadPaging() {
        assertThatThrownBy(() -> productService.searchProducts(null, 0, 10, "password"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> productService.searchProducts(null, 0, 10, "name,sideways"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> productService.searchProducts(null, -1, 10, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> productService.searchProducts(null, 0, Paging.MAX_PAGE_SIZE + 1, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void ordersArePagedNewestFirst_withTheirItemsLoaded() {
        Long[] ids = new Long[5];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = orderService.createOrderWithItems(new Order(), List.of(item(mouseId, 1))).getId();
        }
        orderService.updateOrderStatus(ids[1], OrderStatus.SHIPPED);
        orderService.updateOrderStatus(ids[3], OrderStatus.SHIPPED);
        entityManager.flush();
        entityManager.clear();

        Page<Order> page = orderService.searchOrders(null, 0, 2);
        assertThat(page.getContent()).extracting(Order::getId).containsExactly(ids[4], ids[3]);
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent().get(0).getOrderItems()).hasSize(1);

        Page<Order> shipped = orderService.searchOrders(OrderStatus.SHIPPED, null, null);
        assertThat(shipped.getContent()).extracting(Order::getId).containsExactly(ids[3], ids[1]);
    }

    private static OrderItem item(Long productId, int quantity) {
        Product product = new Product();
        product.setId(productId);
        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(quantity);
        return item;
    }
}
