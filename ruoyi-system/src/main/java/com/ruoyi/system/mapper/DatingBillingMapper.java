package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.*;

public interface DatingBillingMapper
{
    List<DatingServiceOption> selectOptions(@Param("status") String status);
    DatingServiceOption lockOption(Long optionId);
    int insertOption(DatingServiceOption option);
    int updateOption(DatingServiceOption option);
    List<DatingServiceOption> selectOptionsByCodes(@Param("codes") List<String> codes);
    List<DatingCustomer> selectCustomers(@Param("keyword") String keyword, @Param("owner") String owner);
    DatingCustomer lockCustomer(Long userId);
    int updateServiceStatus(@Param("userId") Long userId, @Param("status") String status);
    List<DatingOrder> selectOrders(@Param("orderNo") String orderNo, @Param("status") String status,
        @Param("userId") Long userId, @Param("owner") String owner);
    DatingOrder selectOrder(Long orderId);
    DatingOrder lockOrder(Long orderId);
    DatingOrder selectByRequestKey(@Param("actor") String actor, @Param("key") String key);
    int countOpenOrders(Long userId);
    int insertOrder(DatingOrder order);
    int confirmOrder(DatingOrder order);
    int cancelOrder(DatingOrder order);
    List<DatingEntitlement> selectEntitlements(@Param("userId") Long userId, @Param("owner") String owner);
    DatingEntitlement selectActiveEntitlement(Long userId);
    DatingEntitlement selectEntitlement(@Param("id") Long id, @Param("owner") String owner);
    List<DatingEntitlementLedgerEntry> selectEntitlementLedger(@Param("id") Long id);
    List<DatingIntegrityIssue> selectIntegrityIssues(@Param("userId") Long userId, @Param("owner") String owner);
    int insertEntitlement(DatingEntitlement entitlement);
    int insertPayment(DatingOrder order);
    int insertOpeningLedger(@Param("entitlement") DatingEntitlement entitlement, @Param("actor") String actor);
}
