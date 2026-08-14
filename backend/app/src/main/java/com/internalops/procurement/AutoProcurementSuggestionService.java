package com.internalops.procurement;

import com.internalops.workbench.ProcurementWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class AutoProcurementSuggestionService {
    private static final Logger log = LoggerFactory.getLogger(AutoProcurementSuggestionService.class);
    private final ProcurementWorkflowService workflow;
    private final ApplicationEventPublisher events;

    public AutoProcurementSuggestionService(ProcurementWorkflowService workflow, ApplicationEventPublisher events) {
        this.workflow = workflow;
        this.events = events;
    }

    public void requestRecalculation() {
        events.publishEvent(new RecalculationRequested());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void recalculateAfterCommit(RecalculationRequested ignored) {
        try {
            workflow.generate();
        } catch (IllegalStateException exception) {
            if (!"当前没有需要采购的缺口".equals(exception.getMessage())) {
                log.warn("自动维护待确认采购建议失败，将保留已完成的业务操作: {}", exception.getMessage());
            }
        } catch (RuntimeException exception) {
            log.warn("自动维护待确认采购建议失败，将保留已完成的业务操作: {}", exception.getMessage());
        }
    }

    public record RecalculationRequested() {}
}