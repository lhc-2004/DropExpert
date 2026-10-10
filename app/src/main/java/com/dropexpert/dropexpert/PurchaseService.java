package com.dropexpert.dropexpert;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class PurchaseService {

    private final DropRepository dropRepository;
    private final PurchaseOrderRepository orderRepository;

    public PurchaseService(DropRepository dropRepository, PurchaseOrderRepository orderRepository) {
        this.dropRepository = dropRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public PurchaseResponse purchase(Long dropId, String userId) {
        // 1. READ: load the drop from the database
        Drop drop = dropRepository.findById(dropId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Drop not found"));

        // 2. CHECK: is anything left?
        if (drop.getRemainingUnits() <= 0) {
            return new PurchaseResponse("SOLD_OUT", null);
        }

        // 3. WRITE: take one unit and create the order
        drop.decrementRemaining();
        String purchaseId = UUID.randomUUID().toString();
        orderRepository.save(new PurchaseOrder(purchaseId, dropId, userId));

        return new PurchaseResponse("ACCEPTED", purchaseId);
    }
}