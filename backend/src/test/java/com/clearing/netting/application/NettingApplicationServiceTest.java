package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.NetPositionRepositoryPort;
import com.clearing.netting.domain.port.out.NettingRunRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the preview contract: it runs the same core algorithm / validations as
 * formal execution but never mutates state — obligations stay OPEN and nothing is
 * persisted. {@link MultilateralNettingService} is constructed inside the service,
 * so the real algorithm (not a mock) is exercised here.
 */
@ExtendWith(MockitoExtension.class)
class NettingApplicationServiceTest {

    @Mock
    private NettingRunRepositoryPort runRepository;
    @Mock
    private ObligationRepositoryPort obligationRepository;
    @Mock
    private MemberRepositoryPort memberRepository;
    @Mock
    private NetPositionRepositoryPort positionRepository;
    @Mock
    private NettingRunStatusService statusService;

    private NettingApplicationService service;

    private final LocalDate settleDate = LocalDate.of(2026, 9, 10);
    private final Member a = new Member("A", "Bank A", MemberStatus.ACTIVE);
    private final Member b = new Member("B", "Bank B", MemberStatus.ACTIVE);
    private final Member c = new Member("C", "Bank C", MemberStatus.ACTIVE);

    @BeforeEach
    void setUp() {
        service = new NettingApplicationService(
                runRepository, obligationRepository, memberRepository, positionRepository, statusService);
    }

    @Test
    void previewComputesPositionsButPersistsNothingAndLeavesObligationsOpen() {
        List<TradeObligation> opens = List.of(
                obligation("A", "B", "100"),
                obligation("B", "C", "60"),
                obligation("C", "A", "40"));
        when(obligationRepository.findOpenBySettleDateAndCurrency(settleDate, "USD")).thenReturn(opens);
        when(memberRepository.findByIds(any())).thenReturn(List.of(a, b, c));

        NettingApplicationService.NettingPreviewResult result = service.preview(settleDate, "USD");

        // Net positions are computed by the shared core algorithm.
        assertEquals(3, result.positions().size());
        BigDecimal sum = result.positions().stream()
                .map(NetPosition::getNetAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(BigDecimal.ZERO));
        assertEquals(0, sumNet(result, "A").compareTo(new BigDecimal("-60.00000000")));
        assertEquals(0, sumNet(result, "B").compareTo(new BigDecimal("40.00000000")));
        assertEquals(0, sumNet(result, "C").compareTo(new BigDecimal("20.00000000")));

        // Acceptance: obligations still OPEN and not linked to any run after preview.
        for (TradeObligation o : result.obligations()) {
            assertEquals(ObligationStatus.OPEN, o.getStatus());
            assertNull(o.getNettingRunId());
        }
        for (TradeObligation o : opens) {
            assertEquals(ObligationStatus.OPEN, o.getStatus());
            assertNull(o.getNettingRunId());
        }

        // Nothing was written: no run, no positions, no obligation updates.
        verify(obligationRepository, never()).save(any());
        verify(obligationRepository, never()).saveAll(any());
        verify(positionRepository, never()).saveAll(any());
        verify(runRepository, never()).save(any());
        verify(statusService, never()).saveInNewTx(any());
    }

    @Test
    void previewRejectsSuspendedMemberWithSameValidationAsExecute() {
        Member suspended = new Member("B", "Bank B", MemberStatus.SUSPENDED);
        List<TradeObligation> opens = List.of(obligation("A", "B", "10"));
        when(obligationRepository.findOpenBySettleDateAndCurrency(settleDate, "USD")).thenReturn(opens);
        when(memberRepository.findByIds(any())).thenReturn(List.of(a, suspended));

        DomainException ex = assertThrows(DomainException.class, () -> service.preview(settleDate, "USD"));
        assertEquals("SUSPENDED_MEMBER", ex.getCode());

        // A rejected preview must still leave everything untouched.
        verify(obligationRepository, never()).saveAll(any());
        verify(positionRepository, never()).saveAll(any());
        verify(runRepository, never()).save(any());
        verify(statusService, never()).saveInNewTx(any());
    }

    @Test
    void previewRejectsWhenNoOpenObligations() {
        when(obligationRepository.findOpenBySettleDateAndCurrency(settleDate, "USD")).thenReturn(List.of());
        when(memberRepository.findByIds(any())).thenReturn(List.of());

        DomainException ex = assertThrows(DomainException.class, () -> service.preview(settleDate, "USD"));
        assertEquals("NO_OBLIGATIONS", ex.getCode());

        verify(obligationRepository, never()).saveAll(any());
        verify(positionRepository, never()).saveAll(any());
        verify(runRepository, never()).save(any());
        verify(statusService, never()).saveInNewTx(any());
    }

    @Test
    void executeStillNetsAndPersists() {
        List<TradeObligation> opens = List.of(
                obligation("A", "B", "100"),
                obligation("B", "C", "60"));
        when(obligationRepository.findOpenBySettleDateAndCurrency(settleDate, "USD")).thenReturn(opens);
        when(memberRepository.findByIds(any())).thenReturn(List.of(a, b, c));
        when(statusService.saveInNewTx(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NettingApplicationService.NettingRunResult result = service.execute(settleDate, "USD");

        // Formal execution really changes state: obligations netted and linked to the run.
        assertEquals(NettingRunStatus.COMPLETED, result.run().getStatus());
        for (TradeObligation o : result.obligations()) {
            assertEquals(ObligationStatus.NETTED, o.getStatus());
            assertEquals(result.run().getRunId(), o.getNettingRunId());
        }
        verify(obligationRepository).saveAll(any());
        verify(positionRepository).saveAll(any());
        verify(runRepository).save(any(NettingRun.class));
    }

    private BigDecimal sumNet(NettingApplicationService.NettingPreviewResult r, String memberId) {
        return r.positions().stream()
                .filter(p -> p.getMemberId().equals(memberId))
                .map(NetPosition::getNetAmount)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no position for " + memberId));
    }

    private TradeObligation obligation(String payer, String payee, String amount) {
        return new TradeObligation(
                java.util.UUID.randomUUID().toString(),
                payer,
                payee,
                "USD",
                new BigDecimal(amount),
                settleDate.minusDays(1),
                settleDate,
                ObligationStatus.OPEN,
                null);
    }
}
