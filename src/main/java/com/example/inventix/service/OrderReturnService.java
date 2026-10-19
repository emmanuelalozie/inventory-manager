package com.example.inventix.service;

import com.example.inventix.dto.OrderReturnRequest;
import com.example.inventix.dto.OrderReturnsResponse;

public interface OrderReturnService {

    /**
     * Per product on the order: units sold, already returned and still returnable.
     *
     * @throws com.example.inventix.exception.OrderNotFoundException if there is no order with this id
     */
    OrderReturnsResponse getReturns(Long orderId);

    /**
     * Puts the returned units back in stock with a RETURN movement per product that points at the order. Only shipped
     * and delivered orders take returns, and no product can be returned more often than it was sold on the order.
     * All lines are checked before anything is written.
     *
     * @throws com.example.inventix.exception.InvalidOrderStateException if the order isn't shipped or delivered
     * @throws com.example.inventix.exception.BadRequestException if a product isn't on the order, appears twice, or
     *                                                           more is returned than is still returnable
     */
    OrderReturnsResponse returnItems(Long orderId, OrderReturnRequest request);
}
