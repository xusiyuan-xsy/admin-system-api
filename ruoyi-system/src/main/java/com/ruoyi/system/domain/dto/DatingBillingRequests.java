package com.ruoyi.system.domain.dto;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;

public final class DatingBillingRequests
{
    private DatingBillingRequests() {}
    public record CreateOrder(
        @NotNull @Positive Long userId,
        @NotNull @Positive Long packageId,
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{16,64}") String requestKey,
        @Size(max = 500) String remark) {}
    public record ConfirmPayment(
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal paidAmount,
        @NotBlank @Pattern(regexp = "BANK_TRANSFER|WECHAT|ALIPAY|CASH") String paymentMethod,
        @NotBlank @Size(max = 100) String paymentReference,
        @NotNull @PastOrPresent @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date paidAt,
        @Size(max = 500) String remark) {}
    public record CancelOrder(@NotBlank @Size(max = 500) String reason) {}
}
