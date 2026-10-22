package com.ruoyi.system.domain;

import java.util.Date;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;

public final class DatingSuccess
{
    private DatingSuccess() {}

    public record CloseRequest(
        @NotNull @PastOrPresent @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") Date successAt,
        @NotNull @AssertTrue Boolean bothConfirmed,
        @NotBlank @Size(max=500) String note,
        @NotBlank @Size(max=200) String evidence) {}

    public record Case(Long caseId, Long pairId, Long recommendationId, String lowName, String highName,
        @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") Date successAt, String confirmedBy,
        @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") Date createTime) {}

    public record Member(Long userId, String name, Long matchmakerId, String matchmakerName, Integer retainedTimes) {}
    public record Evidence(String note, String evidence) {}
    public record Detail(Case successCase, List<Member> members, Evidence confirmation) {}
    public record Stats(Long caseCount, Long peopleCount) {}
    public record Matchmaker(Long matchmakerId, String matchmakerName) {}
}
