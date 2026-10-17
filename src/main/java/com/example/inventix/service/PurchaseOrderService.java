package com.example.inventix.service;

import com.example.inventix.dto.PurchaseOrderRequest;
import com.example.inventix.dto.ReceiptRequest;
import com.example.inventix.model.PurchaseOrder;
import com.example.inventix.model.PurchaseOrderStatus;
import org.springframework.data.domain.Page;

public interface PurchaseOrderService {

    /** Purchase orders newest first, optionally filtered. Without page and size every match is returned. */
    Page<PurchaseOrder> searchPurchaseOrders(PurchaseOrderStatus status, Long supplierId, Integer page, Integer size);

    /**
     * @throws com.example.inventix.exception.NotFoundException if there is no purchase order with this id
     */
    PurchaseOrder getPurchaseOrder(Long id);

    /** Creates a DRAFT purchase order. Each product may appear on one line only. */
    PurchaseOrder createPurchaseOrder(PurchaseOrderRequest request);

    /**
     * Replaces a DRAFT's supplier, notes and lines.
     *
     * @throws com.example.inventix.exception.ConflictException if the purchase order isn't a DRAFT
     */
    PurchaseOrder updatePurchaseOrder(Long id, PurchaseOrderRequest request);

    /**
     * DRAFT -> ORDERED (needs at least one line) or CANCELLED, ORDERED -> CANCELLED, PARTIALLY_RECEIVED -> CLOSED.
     *
     * @throws com.example.inventix.exception.ConflictException for any other change
     */
    PurchaseOrder changeStatus(Long id, PurchaseOrderStatus status);

    /**
     * Books received quantities on an ORDERED or PARTIALLY_RECEIVED purchase order. Each line adds stock with a
     * RECEIPT movement. The order becomes RECEIVED once every line is in full, otherwise PARTIALLY_RECEIVED.
     *
     * @throws com.example.inventix.exception.ConflictException if the order can't receive or a quantity exceeds
     *                                                         what is still outstanding on its line
     */
    PurchaseOrder receive(Long id, ReceiptRequest request);

    /**
     * Cancels what is still to come on one line of an ORDERED or PARTIALLY_RECEIVED purchase order. When nothing is
     * left to come on any line, the order becomes CLOSED (something arrived) or CANCELLED (nothing did).
     *
     * @throws com.example.inventix.exception.ConflictException for another status, or a line with nothing left
     */
    PurchaseOrder cancelRemaining(Long id, Long lineId);

    /**
     * Deletes a DRAFT or CANCELLED purchase order. Anything that received stock is kept as a record.
     */
    void deletePurchaseOrder(Long id);

    /**
     * Adds {@code quantity} of a product to the supplier's newest DRAFT, creating the draft if there is none.
     * A product already on that draft is left as it is.
     *
     * @return the draft, whether it was newly created, and whether a line was added
     */
    DraftAddition addToDraft(Long supplierId, Long productId, int quantity);

    record DraftAddition(PurchaseOrder purchaseOrder, boolean created, boolean lineAdded) {
    }
}
