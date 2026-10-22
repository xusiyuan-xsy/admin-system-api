package com.ruoyi.system.service;

import com.ruoyi.system.mapper.DatingWorkflowMapper;
import com.ruoyi.system.domain.DatingRecovery.DispatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DatingDeductionDispatcher
{
    private static final Logger log = LoggerFactory.getLogger(DatingDeductionDispatcher.class);
    private final DatingWorkflowMapper mapper;
    private final DatingDeductionService deductions;
    public DatingDeductionDispatcher(DatingWorkflowMapper mapper, DatingDeductionService deductions)
    { this.mapper = mapper; this.deductions = deductions; }

    // Called only after the service-fact transaction has committed.
    public DispatchResult dispatch(Long pairId)
    {
        var tasks = mapper.selectReadyTasks(pairId);
        int processed = 0, failed = 0, recordFailures = 0;
        for (Long id : tasks)
        {
            try { deductions.process(id); processed++; }
            catch (RuntimeException ex)
            {
                failed++;
                log.error("Dating deduction task {} failed", id, ex);
                try { deductions.failed(id, ex); }
                catch (RuntimeException failure)
                {
                    recordFailures++;
                    log.error("Could not record deduction retry {}", id, failure);
                }
            }
        }
        return new DispatchResult(tasks.size(), processed, failed, recordFailures);
    }
}
