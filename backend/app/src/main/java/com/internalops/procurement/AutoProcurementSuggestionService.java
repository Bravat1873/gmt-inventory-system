package com.internalops.procurement;

import com.internalops.workbench.ProcurementWorkflowService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class AutoProcurementSuggestionService {
    private final ProcurementWorkflowService workflow;
    private final ApplicationEventPublisher events;

    public AutoProcurementSuggestionService(ProcurementWorkflowService workflow, ApplicationEventPublisher events) {
        this.workflow = workflow;
        this.events = events;
    }

    public void requestRecalculation() {
        events.publishEvent(new RecalculationRequested());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void recalculateBeforeCommit(RecalculationRequested ignored) {
        workflow.regenerateInCurrentTransaction();
    }

    public record RecalculationRequested() {}
}
