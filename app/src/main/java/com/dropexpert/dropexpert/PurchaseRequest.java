package com.dropexpert.dropexpert;

import jakarta.validation.constraints.NotBlank;

public record PurchaseRequest(@NotBlank String userId) {
}