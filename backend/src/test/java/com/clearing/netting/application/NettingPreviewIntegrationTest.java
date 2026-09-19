package com.clearing.netting.application;

import com.clearing.netting.adapter.out.persistence.repo.MemberJpaRepository;
import com.clearing.netting.adapter.out.persistence.repo.NetPositionJpaRepository;
import com.clearing.netting.adapter.out.persistence.repo.NettingRunJpaRepository;
import com.clearing.netting.adapter.out.persistence.repo.ObligationJpaRepository;
import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end over the real service + JPA (H2) stack.
 *
 * Acceptance: after {@code preview} the participating obligations are still OPEN and
 * no run/positions exist; only the formal {@code execute} mutates state.
 */
@SpringBootTest
class NettingPreviewIntegrationTest {

    @Autowired
    private NettingApplicationService service;
    @Autowired
    private MemberRepositoryPort memberRepository;
    @Autowired
    private ObligationRepositoryPort obligationRepository;
    @Autowired
    private ObligationJpaRepository obligationJpa;
    @Autowired
    private NettingRunJpaRepository runJpa;
    @Autowired
    private NetPositionJpaRepository positionJpa;
    @Autowired
    private MemberJpaRepository memberJpa;

    private static final LocalDate SETTLE = LocalDate.of(2026, 9, 10);

    @BeforeEach
    void clean() {
        obligationJpa.deleteAllInBatch();
        positionJpa.deleteAllInBatch();
        runJpa.deleteAllInBatch();
        memberJpa.deleteAllInBatch();
    }

    @Test
    void previewLeavesStateUntouchedThenExecuteNets() {
        memberRepository.save(new Member("A", "Bank A", MemberStatus.ACTIVE));
        memberRepository.save(new Member("B", "Bank B", MemberStatus.ACTIVE));
        memberRepository.save(new Member("C", "Bank C", MemberStatus.ACTIVE));
        TradeObligation o1 = obligationRepository.save(TradeObligation.open("A", "B", "USD", new BigDecimal("100"), SETTLE.minusDays(1), SETTLE));
        TradeObligation o2 = obligationRepository.save(TradeObligation.open("B", "C", "USD", new BigDecimal("60"), SETTLE.minusDays(1), SETTLE));
        TradeObligation o3 = obligationRepository.save(TradeObligation.open("C", "A", "USD", new BigDecimal("40"), SETTLE.minusDays(1), SETTLE));

        // ---- preview: computes, persists nothing ----
        NettingApplicationService.NettingPreviewResult preview = service.preview(SETTLE, "USD");
        assertEquals(3, preview.obligations().size());
        assertEquals(3, preview.positions().size());
        BigDecimal sum = preview.positions().stream().map(NetPosition::getNetAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(BigDecimal.ZERO));

        // DB-level acceptance: obligations still OPEN, no run / positions created.
        assertEquals(0, runJpa.count());
        assertEquals(0, positionJpa.count());
        for (String id : List.of(o1.getObligationId(), o2.getObligationId(), o3.getObligationId())) {
            TradeObligation reloaded = obligationRepository.findById(id).orElseThrow();
            assertEquals(ObligationStatus.OPEN, reloaded.getStatus());
            assertNull(reloaded.getNettingRunId());
        }

        // ---- formal execution: state changes now ----
        NettingApplicationService.NettingRunResult executed = service.execute(SETTLE, "USD");
        assertEquals(NettingRunStatus.COMPLETED, executed.run().getStatus());

        assertEquals(1, runJpa.count());
        assertEquals(3, positionJpa.count());
        for (String id : List.of(o1.getObligationId(), o2.getObligationId(), o3.getObligationId())) {
            TradeObligation reloaded = obligationRepository.findById(id).orElseThrow();
            assertEquals(ObligationStatus.NETTED, reloaded.getStatus());
            assertEquals(executed.run().getRunId(), reloaded.getNettingRunId());
        }
    }

    @Test
    void previewAppliesSameSuspendedMemberValidationAsExecute() {
        memberRepository.save(new Member("P", "Bank P", MemberStatus.ACTIVE));
        memberRepository.save(new Member("S", "Bank S", MemberStatus.SUSPENDED));
        obligationRepository.save(TradeObligation.open("P", "S", "USD", new BigDecimal("10"), SETTLE.minusDays(1), SETTLE));

        DomainException ex = assertThrows(DomainException.class, () -> service.preview(SETTLE, "USD"));
        assertEquals("SUSPENDED_MEMBER", ex.getCode());

        // Validation rejection must not create any run either.
        assertEquals(0, runJpa.count());
        assertEquals(0, positionJpa.count());
        assertEquals(ObligationStatus.OPEN, obligationJpa.findAll().get(0).getStatus());
    }
}
