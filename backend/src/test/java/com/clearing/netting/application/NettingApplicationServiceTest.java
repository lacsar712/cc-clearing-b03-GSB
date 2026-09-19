package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.NetPosition;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NettingApplicationServiceTest {

    @Mock
    NettingRunRepositoryPort runRepository;
    @Mock
    ObligationRepositoryPort obligationRepository;
    @Mock
    MemberRepositoryPort memberRepository;
    @Mock
    NetPositionRepositoryPort positionRepository;
    @Mock
    NettingRunStatusService statusService;

    private NettingApplicationService service;
    private final LocalDate settleDate = LocalDate.of(2026, 9, 10);

    @BeforeEach
    void setUp() {
        service = new NettingApplicationService(
                runRepository, obligationRepository, memberRepository, positionRepository, statusService);
    }

    @Test
    void previewComputesNetPositionsButLeavesObligationsOpenAndPersistsNothing() {
        List<TradeObligation> opens = opens();
        when(obligationRepository.findOpenBySettleDateAndCurrency(settleDate, "USD")).thenReturn(opens);
        when(memberRepository.findByIds(any())).thenReturn(activeMembers());

        NettingApplicationService.NettingPreviewResult result = service.preview(settleDate, "usd");

        // lowercase input is normalized
        assertEquals("USD", result.currency());
        assertEquals(3, result.positions().size());
        BigDecimal sum = result.positions().stream()
                .map(NetPosition::getNetAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(BigDecimal.ZERO));

        // obligations returned and source obligations both stay OPEN, unbound
        assertTrue(result.obligations().stream().allMatch(o -> o.getStatus() == ObligationStatus.OPEN));
        assertTrue(opens.stream().allMatch(o -> o.getStatus() == ObligationStatus.OPEN));
        assertTrue(opens.stream().allMatch(o -> o.getNettingRunId() == null));
        // preview positions are transient: not bound to any run
        assertTrue(result.positions().stream().allMatch(p -> p.getRunId() == null));

        verify(obligationRepository, never()).save(any());
        verify(obligationRepository, never()).saveAll(anyList());
        verify(positionRepository, never()).saveAll(anyList());
        verifyNoInteractions(runRepository, statusService);
    }

    @Test
    void previewRejectsSuspendedMemberWithSameValidationAsExecuteAndPersistsNothing() {
        List<TradeObligation> opens = opens();
        when(obligationRepository.findOpenBySettleDateAndCurrency(settleDate, "USD")).thenReturn(opens);
        when(memberRepository.findByIds(any())).thenReturn(List.of(
                new Member("A", "Bank A", MemberStatus.ACTIVE),
                new Member("B", "Bank B", MemberStatus.SUSPENDED),
                new Member("C", "Bank C", MemberStatus.ACTIVE)));

        DomainException ex = assertThrows(DomainException.class,
                () -> service.preview(settleDate, "USD"));
        assertEquals("SUSPENDED_MEMBER", ex.getCode());
        assertTrue(ex.getMessage().contains("B"));

        // rejected validation must not leave any mutation behind
        assertTrue(opens.stream().allMatch(o -> o.getStatus() == ObligationStatus.OPEN));
        verify(obligationRepository, never()).saveAll(anyList());
        verify(positionRepository, never()).saveAll(anyList());
        verifyNoInteractions(runRepository, statusService);
    }

    @Test
    void executeNetsObligationsAndPersistsPositionsBoundToRun() {
        List<TradeObligation> opens = opens();
        when(obligationRepository.findOpenBySettleDateAndCurrency(settleDate, "USD")).thenReturn(opens);
        when(memberRepository.findByIds(any())).thenReturn(activeMembers());
        when(statusService.saveInNewTx(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NettingApplicationService.NettingRunResult result = service.execute(settleDate, "USD");

        assertNotNull(result.run().getRunId());
        assertEquals(NettingRunStatus.COMPLETED, result.run().getStatus());
        assertTrue(result.obligations().stream().allMatch(o -> o.getStatus() == ObligationStatus.NETTED));
        assertTrue(result.obligations().stream()
                .allMatch(o -> result.run().getRunId().equals(o.getNettingRunId())));
        assertTrue(result.positions().stream()
                .allMatch(p -> result.run().getRunId().equals(p.getRunId())));

        verify(obligationRepository).saveAll(opens);
        verify(positionRepository).saveAll(result.positions());
    }

    private List<TradeObligation> opens() {
        return List.of(
                obligation("A", "B", "100"),
                obligation("B", "C", "60"),
                obligation("C", "A", "40"));
    }

    private List<Member> activeMembers() {
        return List.of(
                new Member("A", "Bank A", MemberStatus.ACTIVE),
                new Member("B", "Bank B", MemberStatus.ACTIVE),
                new Member("C", "Bank C", MemberStatus.ACTIVE));
    }

    private TradeObligation obligation(String payer, String payee, String amount) {
        return new TradeObligation(
                payer + "-" + payee + "-ob",
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
