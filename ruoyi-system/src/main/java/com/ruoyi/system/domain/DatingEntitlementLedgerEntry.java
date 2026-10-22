package com.ruoyi.system.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;

public record DatingEntitlementLedgerEntry(Long ledgerId, String entryType, Integer delta,
    Integer balanceBefore, Integer balanceAfter, Long pairId, Long eventId, String createBy,
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date createTime)
{
}
