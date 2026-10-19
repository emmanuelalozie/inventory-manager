package com.example.inventix.service;

import com.example.inventix.dto.OrderResponse;
import com.example.inventix.exception.ConflictException;
import com.example.inventix.exception.NotFoundException;
import com.example.inventix.model.Customer;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.model.Product;
import com.example.inventix.service.impl.CustomerServiceImpl;
import com.example.inventix.service.impl.OrderItemServiceImpl;
import com.example.inventix.service.impl.OrderServiceImpl;
import com.example.inventix.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Customers and the customer on an order, against H2 with the real migrations.
 */
@DataJpaTest
@Import({CustomerServiceImpl.class, ProductServiceImpl.class, OrderItemServiceImpl.class, OrderServiceImpl.class})
class CustomerIntegrationTest {

    @Autowired
    private CustomerService customerService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductService productService;

    @Autowired
    private TestEntityManager entityManager;

    private Long productId;

    @BeforeEach
    void setUp() {
        productId = productService.createProduct(
                new Product(null, "Keyboard", "KB-1", null, new BigDecimal("25.00"), 10, null, null)).getId();
    }

    private static Customer customer(String name, String email, String phone) {
        Customer customer = new Customer();
        customer.setName(name);
        customer.setEmail(email);
        customer.setPhone(phone);
        return customer;
    }

    private List<OrderItem> oneKeyboard() {
        OrderItem item = new OrderItem();
        Product product = new Product();
        product.setId(productId);
        item.setProduct(product);
        item.setQuantity(1);
        return List.of(item);
    }

    @Test
    void customersAreListedByName_andNamesNeedNotBeUnique() {
        customerService.createCustomer(customer("  Zoe Park ", null, null));
        customerService.createCustomer(customer("Ann Lee", "ann@one.example", null));
        customerService.createCustomer(customer("Ann Lee", "ann@two.example", null));

        assertThat(customerService.getCustomers(null)).extracting(Customer::getName)
                .containsExactly("Ann Lee", "Ann Lee", "Zoe Park");
    }

    @Test
    void searchMatchesNameEmailOrPhone_ignoringCase_andTreatsWildcardsLiterally() {
        customerService.createCustomer(customer("Ann Lee", "ann@shop.example", "555-0101"));
        customerService.createCustomer(customer("Bob Diaz", "bob@shop.example", "555-0202"));
        customerService.createCustomer(customer("100% Organic", null, null));

        assertThat(customerService.getCustomers("ANN")).extracting(Customer::getName).containsExactly("Ann Lee");
        assertThat(customerService.getCustomers("bob@")).extracting(Customer::getName).containsExactly("Bob Diaz");
        assertThat(customerService.getCustomers("0202")).extracting(Customer::getName).containsExactly("Bob Diaz");
        assertThat(customerService.getCustomers("%")).extracting(Customer::getName).containsExactly("100% Organic");
        assertThat(customerService.getCustomers("  ")).hasSize(3);
    }

    @Test
    void updateReplacesTheFields_andRejectsAStaleVersion() {
        Customer created = customerService.createCustomer(customer("Ann Lee", "ann@shop.example", null));
        entityManager.flush();

        Customer changes = customer("Ann Lee-Park", null, "555-0101");
        changes.setAddress("1 Main St");
        changes.setVersion(created.getVersion());
        Customer updated = customerService.updateCustomer(created.getId(), changes);
        entityManager.flush();

        assertThat(updated.getName()).isEqualTo("Ann Lee-Park");
        assertThat(updated.getEmail()).isNull();
        assertThat(updated.getAddress()).isEqualTo("1 Main St");

        Customer stale = customer("Someone else", null, null);
        stale.setVersion(created.getVersion() - 1);
        assertThatThrownBy(() -> customerService.updateCustomer(created.getId(), stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void anOrderCanBeTakenForACustomer_andListedByCustomer() {
        Long ann = customerService.createCustomer(customer("Ann Lee", null, null)).getId();
        Long bob = customerService.createCustomer(customer("Bob Diaz", null, null)).getId();

        Long annOrder = orderService.createOrderWithItems(new Order(), ann, oneKeyboard()).getId();
        orderService.createOrderWithItems(new Order(), bob, oneKeyboard());
        Long noCustomer = orderService.createOrderWithItems(new Order(), null, oneKeyboard()).getId();
        entityManager.flush();
        entityManager.clear();

        assertThat(orderService.searchOrders(null, ann, null, null).getContent())
                .extracting(Order::getId).containsExactly(annOrder);
        assertThat(orderService.searchOrders(null, null, null, null).getTotalElements()).isEqualTo(3);

        OrderResponse response = OrderResponse.from(orderService.getOrderById(annOrder));
        assertThat(response.customerId()).isEqualTo(ann);
        assertThat(response.customerName()).isEqualTo("Ann Lee");
        assertThat(OrderResponse.from(orderService.getOrderById(noCustomer)).customerId()).isNull();
    }

    @Test
    void creatingAnOrderForAnUnknownCustomer_isRefusedBeforeAnythingIsReserved() {
        assertThatThrownBy(() -> orderService.createOrderWithItems(new Order(), 999L, oneKeyboard()))
                .isInstanceOf(NotFoundException.class);

        assertThat(productService.getProductById(productId).getQuantity()).isEqualTo(10);
        assertThat(orderService.searchOrders(null, null, null, null).getTotalElements()).isZero();
    }

    @Test
    void theCustomerCanBeChangedOrCleared_evenOnAShippedOrder() {
        Long ann = customerService.createCustomer(customer("Ann Lee", null, null)).getId();
        Long bob = customerService.createCustomer(customer("Bob Diaz", null, null)).getId();
        Long orderId = orderService.createOrderWithItems(new Order(), ann, oneKeyboard()).getId();
        orderService.updateOrderStatus(orderId, OrderStatus.SHIPPED);

        assertThat(orderService.setOrderCustomer(orderId, bob).getCustomer().getName()).isEqualTo("Bob Diaz");
        assertThat(orderService.setOrderCustomer(orderId, null).getCustomer()).isNull();
        assertThat(orderService.getOrderById(orderId).getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void replacingTheItemsKeepsTheCustomer() {
        Long ann = customerService.createCustomer(customer("Ann Lee", null, null)).getId();
        Long orderId = orderService.createOrderWithItems(new Order(), ann, oneKeyboard()).getId();

        Order changes = new Order();
        changes.setOrderItems(oneKeyboard());
        orderService.updateOrder(orderId, changes);
        entityManager.flush();
        entityManager.clear();

        assertThat(orderService.getOrderById(orderId).getCustomer().getId()).isEqualTo(ann);
    }

    @Test
    void aCustomerWithOrdersCantBeDeleted_butOneWithoutCan() {
        Long ann = customerService.createCustomer(customer("Ann Lee", null, null)).getId();
        Long bob = customerService.createCustomer(customer("Bob Diaz", null, null)).getId();
        orderService.createOrderWithItems(new Order(), ann, oneKeyboard());

        assertThatThrownBy(() -> customerService.deleteCustomer(ann))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("has orders");

        customerService.deleteCustomer(bob);
        assertThatThrownBy(() -> customerService.getCustomer(bob)).isInstanceOf(NotFoundException.class);
    }
}
