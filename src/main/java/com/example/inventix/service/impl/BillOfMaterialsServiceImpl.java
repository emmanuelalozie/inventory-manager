package com.example.inventix.service.impl;

import com.example.inventix.dto.ComponentsRequest;
import com.example.inventix.exception.BadRequestException;
import com.example.inventix.model.Product;
import com.example.inventix.model.ProductComponent;
import com.example.inventix.model.ProductSource;
import com.example.inventix.repository.ProductComponentRepository;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.service.BillOfMaterialsService;
import com.example.inventix.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional
public class BillOfMaterialsServiceImpl implements BillOfMaterialsService {

    private final ProductComponentRepository componentRepository;
    private final ProductRepository productRepository;
    private final ProductService productService;

    @Autowired
    public BillOfMaterialsServiceImpl(ProductComponentRepository componentRepository,
                                      ProductRepository productRepository, ProductService productService) {
        this.componentRepository = componentRepository;
        this.productRepository = productRepository;
        this.productService = productService;
    }

    @Override
    public List<ProductComponent> getComponents(Long productId) {
        productService.getProductById(productId); // 404 for an unknown product
        return componentRepository.findByProductId(productId);
    }

    @Override
    public List<ProductComponent> replaceComponents(Long productId, List<ComponentsRequest.Line> lines) {
        Product product = productService.getProductById(productId);
        if (!lines.isEmpty() && product.getSource() != ProductSource.MAKE) {
            throw new BadRequestException("Only products made in-house (source MAKE) can have components");
        }
        Set<Long> seen = new HashSet<>();
        for (ComponentsRequest.Line line : lines) {
            Long componentId = line.componentId();
            if (componentId.equals(productId)) {
                throw new BadRequestException("A product can't be a component of itself");
            }
            if (!seen.add(componentId)) {
                throw new BadRequestException("Component " + componentId + " appears more than once");
            }
            if (!productRepository.existsById(componentId)) {
                throw new BadRequestException("Component " + componentId + " doesn't exist");
            }
            if (uses(componentId, productId)) {
                throw new BadRequestException("Component " + componentId + " is itself made from product " + productId
                        + ", which would make a loop");
            }
        }

        componentRepository.deleteByProductId(productId);
        componentRepository.flush();
        for (ComponentsRequest.Line line : lines) {
            ProductComponent component = new ProductComponent();
            component.setProductId(productId);
            component.setComponent(productRepository.findById(line.componentId()).orElseThrow());
            component.setQuantity(line.quantity());
            componentRepository.save(component);
        }
        componentRepository.flush();
        return componentRepository.findByProductId(productId);
    }

    // True if product `from` uses `target` anywhere in its bill of materials (following components of components).
    private boolean uses(Long from, Long target) {
        Deque<Long> toVisit = new ArrayDeque<>(List.of(from));
        Set<Long> visited = new HashSet<>();
        while (!toVisit.isEmpty()) {
            Long current = toVisit.pop();
            if (!visited.add(current)) {
                continue;
            }
            for (Long componentId : componentRepository.findComponentIds(current)) {
                if (componentId.equals(target)) {
                    return true;
                }
                toVisit.push(componentId);
            }
        }
        return false;
    }
}
