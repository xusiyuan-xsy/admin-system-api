package com.ruoyi.system.domain.dto;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;

public final class DatingWorkflowRequests
{
    private DatingWorkflowRequests() {}
    public record FollowupAction(@NotBlank @Pattern(regexp = "CLAIM|UPDATE|RELEASE") String action,
        @NotNull @PositiveOrZero Long version, String status,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date dueAt,
        @NotBlank @Size(max = 500) String note) {}
    public record Retry(@NotBlank(message = "请填写重试原因")
        @Size(max = 500, message = "重试原因不能超过500字") String reason) {}
    public record Assign(@NotNull @Positive Long matchmakerId,
        Long expectedMatchmakerId, @NotBlank @Size(max = 500) String reason) {}
    public record Recommend(@NotNull @Positive Long firstUserId, @NotNull @Positive Long secondUserId,
        @NotBlank @Size(max = 500) String reason,
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{16,64}") String requestKey) {}
    public record Intent(@NotNull @Positive Long userId,
        @NotBlank @Pattern(regexp = "WILLING|REJECTED") String intention,
        @NotBlank @Size(max = 500) String reason) {}
    public record ServiceEvent(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,49}") String optionCode,
        @NotNull @PastOrPresent @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date occurredAt,
        @NotBlank @Size(max = 500) String note, @Size(max = 200) String evidence,
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{16,64}") String requestKey) {}
    public record Schedule(@NotNull @Future @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date plannedAt,
        @NotBlank @Size(max = 200) String place, @NotBlank @Size(max = 500) String reason,
        @AssertTrue boolean bothConfirmed) {}
    public record AppointmentAction(@NotBlank @Pattern(regexp = "COMPLETE|CANCEL|NO_SHOW") String action,
        @PastOrPresent @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date occurredAt,
        @NotBlank @Size(max = 500) String reason, @Size(max = 200) String evidence) {}
}
