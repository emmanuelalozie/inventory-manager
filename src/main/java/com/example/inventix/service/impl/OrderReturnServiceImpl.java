package com.example.inventix.service.impl;

import com.example.inventix.dto.OrderReturnRequest;
import com.example.inventix.dto.OrderReturnsResponse;
import com.example.inventix.exception.BadRequestException;
import com.example.inventix.exception.InvalidOrderStateException;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.repository.StockMovementRepository;
import com.example.inventix.service.OrderReturnService;
import com.example.inventix.service.OrderService;
import com.example.inventix.service.ProductService;
import com.example.inventix.service.StockReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Returns are counted from the order's RETURN movements, per product (an order can list a product on two lines),
// so no extra columns are needed.
@Service
@Transactional
public class OrderReturnServiceImpl implements OrderReturnService {

    private final OrderService orderService;
    private final ProductService productService;
    private final StockMovementRepository stockMovementRepository;

    @Autowired
    public OrderReturnServiceImpl(OrderService orderService, ProductService productService,
                                  StockMovementRepository stockMovementRepository) {
        this.orderService = orderService;
        this.productService = productService;
        this.stockMovementRepository = stockMovementRepository;
    }

    @Override
    public OrderReturnsResponse getReturns(Long orderId) {
        return summarize(orderService.getOrderById(orderId));
    }

    @Override
    public OrderReturnsResponse returnItems(Long orderId, OrderReturnRequest request) {
        Order order = orderService.getOrderById(orderId);
        if (order.getStatus() != OrderStatus.SHIPPED && order.getStatus() != OrderStatus.DELIVERED) {
            throw new InvalidOrderStateException("Order " + orderId + " is " + order.getStatus()
                    + "; only shipped or delivered orders take returns (cancel or edit a pending order instead)");
        }

        Map<Long, OrderReturnsResponse.Line> byProduct = new HashMap<>();
        summarize(order).lines().forEach(line -> byProduct.put(line.productId(), line));
        Set<Long> seen = new HashSet<>();
        for (OrderReturnRequest.Line line : request.lines()) {
            OrderReturnsResponse.Line onOrder = byProduct.get(line.productId());
            if (onOrder == null) {
                throw new BadRequestException("Product " + line.productId() + " isn't on order " + orderId);
            }
            if (!seen.add(line.productId())) {
                throw new BadRequestException("Product '" + onOrder.productName() + "' is listed more than once");
            }
            if (line.quantity() > onOrder.quantityReturnable()) {
                throw new BadRequestException("Only " + onOrder.quantityReturnable() + " of '" + onOrder.productName()
                        + "' can still be returned on order " + orderId);
            }
        }

        String note = "Returned from order #" + orderId
                + (request.note() == null || request.note().isBlank() ? "" : ": " + request.note().strip());
        for (OrderReturnRequest.Line line : request.lines()) {
            productService.changeStock(line.productId(), line.quantity(), MovementReason.RETURN, note,
                    StockReference.order(orderId));
        }
        return summarize(order);
    }

    private OrderReturnsResponse summarize(Order order) {
        Map<Long, Integer> returned = new HashMap<>();
        for (Object[] row : stockMovementRepository.sumReturnedByProduct(order.getId())) {
            returned.put((Long) row[0], ((Number) row[1]).intValue());
        }
        // Sold units per product, in the order the items were added.
        Map<Long, OrderItem> firstItem = new LinkedHashMap<>();
        Map<Long, Integer> sold = new HashMap<>();
        for (OrderItem item : order.getOrderItems()) {
            Long productId = item.getProduct().getId();
            firstItem.putIfAbsent(productId, item);
            sold.merge(productId, item.getQuantity(), Integer::sum);
        }
        List<OrderReturnsResponse.Line> lines = firstItem.entrySet().stream()
                .map(e -> OrderReturnsResponse.Line.of(e.getKey(), e.getValue().getProduct().getSku(),
                        e.getValue().getProduct().getName(), sold.get(e.getKey()),
                        returned.getOrDefault(e.getKey(), 0)))
                .toList();
        return new OrderReturnsResponse(order.getId(), lines);
    }
}
