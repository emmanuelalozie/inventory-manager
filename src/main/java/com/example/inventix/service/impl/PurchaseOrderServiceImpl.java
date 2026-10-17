package com.example.inventix.service.impl;

import com.example.inventix.dto.PurchaseOrderRequest;
import com.example.inventix.dto.ReceiptRequest;
import com.example.inventix.exception.BadRequestException;
import com.example.inventix.exception.ConflictException;
import com.example.inventix.exception.NotFoundException;
import com.example.inventix.exception.ProductNotFoundException;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Product;
import com.example.inventix.model.PurchaseOrder;
import com.example.inventix.model.PurchaseOrderLine;
import com.example.inventix.model.PurchaseOrderStatus;
import com.example.inventix.model.Supplier;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.repository.PurchaseOrderRepository;
import com.example.inventix.repository.SupplierRepository;
import com.example.inventix.service.Paging;
import com.example.inventix.service.ProductService;
import com.example.inventix.service.PurchaseOrderService;
import com.example.inventix.service.StockReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class PurchaseOrderServiceImpl implements PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final ProductService productService;

    @Autowired
    public PurchaseOrderServiceImpl(PurchaseOrderRepository purchaseOrderRepository,
                                    SupplierRepository supplierRepository,
                                    ProductRepository productRepository,
                                    ProductService productService) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.productService = productService;
    }

    @Override
    public Page<PurchaseOrder> searchPurchaseOrders(PurchaseOrderStatus status, Long supplierId,
                                                    Integer page, Integer size) {
        Page<Long> ids = purchaseOrderRepository.findIds(status, supplierId,
                Paging.pageable(page, size, Sort.by(Sort.Direction.DESC, "id")));
        Map<Long, PurchaseOrder> byId = purchaseOrderRepository.findByIdIn(ids.getContent()).stream()
                .collect(Collectors.toMap(PurchaseOrder::getId, Function.identity()));
        return ids.map(byId::get);
    }

    @Override
    public PurchaseOrder getPurchaseOrder(Long id) {
        return purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Purchase order not found with id: " + id));
    }

    @Override
    public PurchaseOrder createPurchaseOrder(PurchaseOrderRequest request) {
        PurchaseOrder order = new PurchaseOrder();
        order.setStatus(PurchaseOrderStatus.DRAFT);
        order.setSupplier(findSupplier(request.supplierId()));
        order.setNotes(blankToNull(request.notes()));
        addLines(order, request.linesOrEmpty());
        return purchaseOrderRepository.save(order);
    }

    @Override
    public PurchaseOrder updatePurchaseOrder(Long id, PurchaseOrderRequest request) {
        PurchaseOrder order = getPurchaseOrder(id);
        if (request.version() != null && !request.version().equals(order.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(PurchaseOrder.class, id);
        }
        if (order.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new ConflictException("Purchase order " + id + " is " + order.getStatus()
                    + "; only DRAFT purchase orders can be edited");
        }
        order.setSupplier(findSupplier(request.supplierId()));
        order.setNotes(blankToNull(request.notes()));
        order.getLines().clear(); // orphan removal deletes the old lines
        addLines(order, request.linesOrEmpty());
        order.setUpdatedAt(LocalDateTime.now()); // a lines-only change still bumps the version
        return purchaseOrderRepository.save(order);
    }

    @Override
    public PurchaseOrder changeStatus(Long id, PurchaseOrderStatus status) {
        PurchaseOrder order = getPurchaseOrder(id);
        PurchaseOrderStatus current = order.getStatus();
        if (current == status) {
            return order;
        }
        if (!current.canTransitionTo(status)) {
            throw new ConflictException("Cannot change purchase order " + id + " from " + current + " to " + status);
        }
        if (status == PurchaseOrderStatus.ORDERED) {
            if (order.getLines().isEmpty()) {
                throw new ConflictException("Purchase order " + id + " has no lines; add products before ordering");
            }
            order.setOrderedAt(LocalDateTime.now());
        }
        order.setStatus(status);
        return purchaseOrderRepository.save(order);
    }

    @Override
    public PurchaseOrder receive(Long id, ReceiptRequest request) {
        PurchaseOrder order = getPurchaseOrder(id);
        if (order.getStatus() != PurchaseOrderStatus.ORDERED && order.getStatus() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new ConflictException("Purchase order " + id + " is " + order.getStatus()
                    + "; only ORDERED or PARTIALLY_RECEIVED purchase orders can receive stock");
        }
        Map<Long, PurchaseOrderLine> linesById = order.getLines().stream()
                .collect(Collectors.toMap(PurchaseOrderLine::getId, Function.identity()));

        // Check every line first, so a bad line leaves the whole receipt unbooked.
        Set<Long> seen = new HashSet<>();
        for (ReceiptRequest.Line received : request.lines()) {
            PurchaseOrderLine line = linesById.get(received.lineId());
            if (line == null) {
                throw new NotFoundException("Line " + received.lineId() + " is not on purchase order " + id);
            }
            if (!seen.add(received.lineId())) {
                throw new BadRequestException("Line " + received.lineId() + " is listed more than once");
            }
            if (received.quantity() > line.getQuantityRemaining()) {
                throw new ConflictException("Line " + line.getId() + " (" + line.getProduct().getName() + ") has "
                        + line.getQuantityRemaining() + " still to receive; " + received.quantity() + " is too many");
            }
        }

        String note = "Received on purchase order #" + id
                + (request.note() == null || request.note().isBlank() ? "" : ": " + request.note().strip());
        for (ReceiptRequest.Line received : request.lines()) {
            PurchaseOrderLine line = linesById.get(received.lineId());
            productService.changeStock(line.getProduct().getId(), received.quantity(), MovementReason.RECEIPT, note,
                    StockReference.purchaseOrder(id));
            line.setQuantityReceived(line.getQuantityReceived() + received.quantity());
            if (line.getUnitCost() != null) {
                line.getProduct().setUnitCost(line.getUnitCost()); // last purchase cost
            }
        }

        order.setStatus(order.isSettled() ? order.settledStatus() : PurchaseOrderStatus.PARTIALLY_RECEIVED);
        // Always write the order row, so two receipts at the same time conflict on its version instead of both
        // booking the same outstanding quantity.
        order.setUpdatedAt(LocalDateTime.now());
        return purchaseOrderRepository.save(order);
    }

    @Override
    public PurchaseOrder cancelRemaining(Long id, Long lineId) {
        PurchaseOrder order = getPurchaseOrder(id);
        if (order.getStatus() != PurchaseOrderStatus.ORDERED && order.getStatus() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new ConflictException("Purchase order " + id + " is " + order.getStatus()
                    + "; lines can only be cancelled on ORDERED or PARTIALLY_RECEIVED purchase orders"
                    + (order.getStatus() == PurchaseOrderStatus.DRAFT ? " (edit the draft instead)" : ""));
        }
        PurchaseOrderLine line = order.getLines().stream().filter(l -> l.getId().equals(lineId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Line " + lineId + " is not on purchase order " + id));
        if (line.getQuantityRemaining() == 0) {
            throw new ConflictException("Line " + lineId + " has nothing left to cancel");
        }
        line.setQuantityCancelled(line.getQuantityCancelled() + line.getQuantityRemaining());
        if (order.isSettled()) {
            order.setStatus(order.settledStatus());
        }
        order.setUpdatedAt(LocalDateTime.now());
        return purchaseOrderRepository.save(order);
    }

    @Override
    public void deletePurchaseOrder(Long id) {
        PurchaseOrder order = getPurchaseOrder(id);
        if (order.getStatus() != PurchaseOrderStatus.DRAFT && order.getStatus() != PurchaseOrderStatus.CANCELLED) {
            throw new ConflictException("Purchase order " + id + " is " + order.getStatus()
                    + "; only DRAFT or CANCELLED purchase orders can be deleted");
        }
        purchaseOrderRepository.delete(order);
    }

    @Override
    public DraftAddition addToDraft(Long supplierId, Long productId, int quantity) {
        PurchaseOrder draft = purchaseOrderRepository
                .findFirstBySupplierIdAndStatusOrderByIdDesc(supplierId, PurchaseOrderStatus.DRAFT)
                .orElse(null);
        boolean created = draft == null;
        if (created) {
            draft = new PurchaseOrder();
            draft.setStatus(PurchaseOrderStatus.DRAFT);
            draft.setSupplier(findSupplier(supplierId));
        } else if (draft.getLines().stream().anyMatch(line -> line.getProduct().getId().equals(productId))) {
            return new DraftAddition(draft, false, false);
        }
        addLines(draft, List.of(new PurchaseOrderRequest.Line(productId, quantity, null)));
        draft.setUpdatedAt(LocalDateTime.now());
        return new DraftAddition(purchaseOrderRepository.save(draft), created, true);
    }

    private Supplier findSupplier(Long supplierId) {
        return supplierRepository.findById(supplierId)
                .orElseThrow(() -> new BadRequestException("Supplier " + supplierId + " doesn't exist"));
    }

    // Loads each product (not a proxy), so the response can show its name after the transaction.
    private void addLines(PurchaseOrder order, List<PurchaseOrderRequest.Line> lines) {
        Map<Long, Product> products = new HashMap<>();
        order.getLines().forEach(line -> products.put(line.getProduct().getId(), line.getProduct()));
        for (PurchaseOrderRequest.Line requested : lines) {
            if (products.containsKey(requested.productId())) {
                throw new BadRequestException("Product " + requested.productId()
                        + " appears more than once; use one line per product");
            }
            Product product = productRepository.findById(requested.productId())
                    .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + requested.productId()));
            products.put(product.getId(), product);

            PurchaseOrderLine line = new PurchaseOrderLine();
            line.setProduct(product);
            line.setQuantityOrdered(requested.quantity());
            line.setUnitCost(requested.unitCost());
            order.addLine(line);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
