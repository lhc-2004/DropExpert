package com.dropexpert.dropexpert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/drops")
public class PurchaseController {

    private final PurchaseService purchaseService;

    public PurchaseController(PurchaseService purchaseService) {
        this.purchaseService = purchaseService;
    }

    @PostMapping("/{dropId}/purchase")
    public ResponseEntity<PurchaseResponse> purchase(@PathVariable Long dropId,
                                                     @Valid @RequestBody PurchaseRequest request) {
        PurchaseResponse response = purchaseService.purchase(dropId, request.userId());

        if ("SOLD_OUT".equals(response.status())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        // 202 Accepted = "received, still processing"
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}