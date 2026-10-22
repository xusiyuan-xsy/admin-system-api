package com.ruoyi.system.service;

import java.math.BigInteger;
import java.util.*;
import com.ruoyi.common.core.domain.entity.DatingPackage;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.domain.dto.DatingBillingRequests.*;
import com.ruoyi.system.mapper.*;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import static com.ruoyi.system.service.DatingPackageRules.require;

@Service
public class DatingBillingService
{
    private final DatingBillingMapper mapper;
    private final DatingPackageMapper packages;
    private final DatingPackageRules rules;
    private final Validator validator;

    public DatingBillingService(DatingBillingMapper mapper, DatingPackageMapper packages,
                                DatingPackageRules rules, Validator validator)
    {
        this.mapper = mapper;
        this.packages = packages;
        this.rules = rules;
        this.validator = validator;
    }

    private void validate(Object request)
    {
        var errors = validator.validate(request);
        if (!errors.isEmpty()) throw new ServiceException(errors.iterator().next().getMessage());
    }

    public List<DatingServiceOption> options(String status) { return mapper.selectOptions(status); }

    @Transactional(rollbackFor = Exception.class)
    public void saveOption(DatingServiceOption option, String actor)
    {
        validate(option);
        if (option.getOptionId() == null)
        {
            // Keep the full UUID entropy while using a numeric suffix within the 50-character limit.
            option.setCode("OPTION_" + new BigInteger(IdUtils.simpleUUID(), 16).toString());
            option.setCreateBy(actor);
            mapper.insertOption(option);
        }
        else
        {
            DatingServiceOption old = mapper.lockOption(option.getOptionId());
            require(old != null, "服务选项不存在");
            require(old.getCode().equals(option.getCode()), "服务选项编码创建后不可修改");
            option.setUpdateBy(actor);
            mapper.updateOption(option);
        }
    }

    public List<DatingCustomer> customers(String keyword, String owner)
    {
        List<DatingCustomer> users = mapper.selectCustomers(keyword, owner);
        for (DatingCustomer user : users)
        {
            String phone = user.getPhone();
            user.setPhone(phone == null || phone.length() < 7 ? "***"
                : phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4));
        }
        return users;
    }

    public List<DatingPackage> availablePackages()
    {
        DatingPackage query = new DatingPackage();
        query.setStatus("0");
        return packages.selectDatingPackageList(query).stream().filter(pkg -> {
            try { rules.validate(pkg); return true; }
            catch (ServiceException ex) { return false; }
        }).toList();
    }

    public List<DatingOrder> orders(String orderNo, String status, Long userId, String owner)
    {
        return mapper.selectOrders(orderNo, status, userId, owner);
    }

    public List<DatingEntitlement> entitlements(Long userId, String owner)
    {
        return mapper.selectEntitlements(userId, owner);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> entitlementDetail(Long id, String owner)
    {
        DatingEntitlement entitlement = mapper.selectEntitlement(id, owner);
        require(entitlement != null, "权益不存在或无权访问");
        return Map.of("entitlement", entitlement, "ledger", mapper.selectEntitlementLedger(id),
            "issues", mapper.selectIntegrityIssues(entitlement.getUserId(), owner));
    }

    public List<DatingIntegrityIssue> integrityIssues(Long userId, String owner)
    {
        return mapper.selectIntegrityIssues(userId, owner);
    }

    public DatingOrder detail(Long id, String actor, boolean all)
    {
        DatingOrder order = mapper.selectOrder(id);
        require(order != null, "订单不存在");
        access(order.getEffectiveOwner(), actor, all);
        return order;
    }

    private void access(String owner, String actor, boolean all)
    {
        require(all || Objects.equals(owner, actor), "无权操作该客户或订单");
    }

    private DatingCustomer lockCustomer(Long id, String actor, boolean all)
    {
        DatingCustomer customer = mapper.lockCustomer(id);
        require(customer != null, "客户不存在");
        access(customer.getEffectiveOwner(), actor, all);
        return customer;
    }

    private void serviceAllowed(DatingCustomer customer)
    {
        require("0".equals(customer.getStatus()), "客户已停用");
        require(!"SUCCESS_CLOSED".equals(customer.getServiceStatus()), "客户已成功结案，不能开通服务");
        require(mapper.selectActiveEntitlement(customer.getUserId()) == null, "客户已有服务中的权益");
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public DatingOrder create(CreateOrder request, String actor, boolean all)
    {
        validate(request);
        DatingCustomer customer = lockCustomer(request.userId(), actor, all);
        DatingOrder replay = mapper.selectByRequestKey(actor, request.requestKey());
        if (replay != null)
        {
            require(Objects.equals(replay.getUserId(), request.userId())
                && Objects.equals(replay.getPackageId(), request.packageId())
                && Objects.equals(replay.getRemark(), request.remark()), "请求标识已被其他订单占用");
            return detail(replay.getOrderId(), actor, all);
        }
        serviceAllowed(customer);
        require(mapper.countOpenOrders(request.userId()) == 0, "客户存在待收款订单，请先处理");
        DatingPackage pkg = packages.lockDatingPackageById(request.packageId());
        require(pkg != null && "0".equals(pkg.getStatus()), "套餐不存在或已停用");
        String snapshot = rules.snapshot(pkg);
        DatingOrder order = new DatingOrder();
        order.setOrderNo("ML" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT));
        order.setUserId(request.userId());
        order.setPackageId(pkg.getPackageId());
        order.setPackageName(pkg.getPackageName());
        order.setAmount(pkg.getPrice());
        order.setServiceTimes(pkg.getServiceTimes());
        order.setRuleSnapshot(snapshot);
        order.setRequestKey(request.requestKey());
        order.setCreateBy(actor);
        order.setRemark(request.remark());
        mapper.insertOrder(order);
        return detail(order.getOrderId(), actor, all);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public DatingOrder confirm(Long id, ConfirmPayment request, String actor, boolean all)
    {
        validate(request);
        DatingOrder original = detail(id, actor, all);
        // Every mutation locks the customer before the order to serialize renewals.
        DatingCustomer customer = lockCustomer(original.getUserId(), actor, all);
        DatingOrder order = mapper.lockOrder(id);
        require(order.getAmount().compareTo(request.paidAmount()) == 0, "实收金额必须等于订单金额");
        if ("PAID".equals(order.getStatus()))
        {
            require(Objects.equals(order.getPaymentMethod(), request.paymentMethod())
                && Objects.equals(order.getPaymentReference(), request.paymentReference())
                && Objects.equals(order.getPaidAt(), request.paidAt()), "订单已收款，不能再次修改收款信息");
            return detail(id, actor, all);
        }
        require("UNPAID".equals(order.getStatus()), "仅待收款订单可以确认收款");
        serviceAllowed(customer);
        order.setPaidAmount(request.paidAmount());
        order.setPaymentMethod(request.paymentMethod());
        order.setPaymentReference(request.paymentReference());
        order.setPaidAt(request.paidAt());
        order.setConfirmedBy(actor);
        require(mapper.confirmOrder(order) == 1, "订单状态已变化，请刷新");
        order.setRemark(request.remark());
        mapper.insertPayment(order);
        DatingEntitlement entitlement = new DatingEntitlement();
        entitlement.setOrderId(id);
        entitlement.setUserId(order.getUserId());
        entitlement.setTotalTimes(order.getServiceTimes());
        entitlement.setRuleSnapshot(order.getRuleSnapshot());
        entitlement.setCreateBy(actor);
        mapper.insertEntitlement(entitlement);
        mapper.insertOpeningLedger(entitlement, actor);
        require(mapper.updateServiceStatus(order.getUserId(), "SERVING") == 1, "客户服务状态更新失败");
        return detail(id, actor, all);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void cancel(Long id, CancelOrder request, String actor, boolean all)
    {
        validate(request);
        DatingOrder original = detail(id, actor, all);
        lockCustomer(original.getUserId(), actor, all);
        DatingOrder order = mapper.lockOrder(id);
        require("UNPAID".equals(order.getStatus()), "仅待收款订单可以取消");
        order.setCancelReason(request.reason());
        order.setUpdateBy(actor);
        require(mapper.cancelOrder(order) == 1, "订单状态已变化，请刷新");
    }
}
