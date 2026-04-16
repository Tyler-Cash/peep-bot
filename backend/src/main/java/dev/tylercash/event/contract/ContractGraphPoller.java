package dev.tylercash.event.contract;

import dev.tylercash.event.contract.model.Contract;
import dev.tylercash.event.contract.model.ContractState;
import dev.tylercash.event.contract.repository.ContractRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContractGraphPoller {

    private final ContractRepository contractRepo;
    private final ContractPinnedMessageService pinnedMessageService;

    @Scheduled(fixedRate = 60, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    @SchedulerLock(name = "contractGraphPoller")
    public void refreshAll() {
        List<Contract> open = contractRepo.findByStateIn(List.of(ContractState.OPEN));
        log.info("Refreshing graphs for {} open contracts", open.size());
        for (Contract contract : open) {
            try {
                pinnedMessageService.refresh(contract);
            } catch (Exception e) {
                log.error("Failed to refresh graph for contract {}", contract.getId(), e);
            }
        }
    }
}
