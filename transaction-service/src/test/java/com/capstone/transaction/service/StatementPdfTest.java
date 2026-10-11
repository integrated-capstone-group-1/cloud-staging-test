package com.capstone.transaction.service;

import com.capstone.transaction.entity.oracle.TransactionMaster;
import com.capstone.transaction.repository.oracle.TransactionMasterRepository;
import com.capstone.transaction.service.statement.StatementDocumentModel;
import com.capstone.transaction.service.statement.StatementItem;
import com.capstone.transaction.service.statement.StatementPdfGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatementPdfTest {

    @Mock
    private TransactionMasterRepository txnMasterRepository;

    @Spy
    private StatementPdfGenerator statementPdfGenerator = new StatementPdfGenerator();

    @InjectMocks
    private StatementService statementService;

    private static final String CUSTOMER_ID = "cust-user-001";
    private static final String ACCOUNT_ID = "acct-php-01";

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                CUSTOMER_ID, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("DIG-92 AC #1: GET /v1/statements?from=&to= returns valid binary PDF")
    void shouldGenerateValidStatementPdf() {
        TransactionMaster txn1 = TransactionMaster.builder()
                .txnId("txn-pdf-1")
                .txnType("DEPOSIT")
                .txnStatus("COMMITTED")
                .mutationAmount(new BigDecimal("5000.0000"))
                .currencyCode("PHP")
                .creditAccountId(ACCOUNT_ID)
                .customerId(CUSTOMER_ID)
                .createdAt(LocalDateTime.now().minusDays(5))
                .build();

        TransactionMaster txn2 = TransactionMaster.builder()
                .txnId("txn-pdf-2")
                .txnType("TRANSFER")
                .txnStatus("COMMITTED")
                .mutationAmount(new BigDecimal("1200.0000"))
                .currencyCode("PHP")
                .debitAccountId(ACCOUNT_ID)
                .creditAccountId("acct-target-02")
                .customerId(CUSTOMER_ID)
                .createdAt(LocalDateTime.now().minusDays(2))
                .build();

        when(txnMasterRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(txn1, txn2));

        byte[] pdfBytes = statementService.generateAccountStatement(
                LocalDate.now().minusDays(10),
                LocalDate.now(),
                ACCOUNT_ID,
                CUSTOMER_ID
        );

        assertThat(pdfBytes).isNotNull();
        assertThat(pdfBytes.length).isGreaterThan(500);

        String pdfText = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        assertThat(pdfText).startsWith("%PDF-1.4");
        assertThat(pdfText).contains("%%EOF");
        assertThat(pdfText).contains("xref");
        assertThat(pdfText).contains("/Catalog");
        assertThat(pdfText).contains("ACCOUNT TRANSACTION STATEMENT");
        assertThat(pdfText).contains(ACCOUNT_ID);
        assertThat(pdfText).contains("CAPSTONE DIGITAL BANK");
    }

    @Test
    @DisplayName("DIG-92 AC #2 Reusability: Generator generates monthly e-statement")
    void shouldGenerateMonthlyEStatement() {
        TransactionMaster txn = TransactionMaster.builder()
                .txnId("txn-month-1")
                .txnType("TRANSFER")
                .txnStatus("COMMITTED")
                .mutationAmount(new BigDecimal("350.0000"))
                .currencyCode("PHP")
                .debitAccountId(ACCOUNT_ID)
                .customerId(CUSTOMER_ID)
                .createdAt(LocalDateTime.of(2026, 10, 2, 10, 30))
                .build();

        when(txnMasterRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(txn));

        byte[] pdfBytes = statementService.generateMonthlyEStatement(2026, 10, ACCOUNT_ID, CUSTOMER_ID);

        assertThat(pdfBytes).isNotNull();
        String pdfText = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        assertThat(pdfText).startsWith("%PDF-1.4");
        assertThat(pdfText).contains("MONTHLY ELECTRONIC STATEMENT");
        assertThat(pdfText).contains("%%EOF");
    }

    @Test
    @DisplayName("DIG-92 AC #2 Reusability: Generator generates EOD batch reconciliation report")
    void shouldGenerateEodReconciliationReport() {
        TransactionMaster txnCommitted = TransactionMaster.builder()
                .txnId("txn-eod-1")
                .txnType("TRANSFER")
                .txnStatus("COMMITTED")
                .mutationAmount(new BigDecimal("2000.0000"))
                .currencyCode("PHP")
                .createdAt(LocalDateTime.now())
                .build();

        TransactionMaster txnFailed = TransactionMaster.builder()
                .txnId("txn-eod-2")
                .txnType("WITHDRAWAL")
                .txnStatus("FAILED")
                .mutationAmount(new BigDecimal("500.0000"))
                .currencyCode("PHP")
                .createdAt(LocalDateTime.now())
                .build();

        when(txnMasterRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(txnCommitted, txnFailed));

        byte[] pdfBytes = statementService.generateEodReport(LocalDate.now());

        assertThat(pdfBytes).isNotNull();
        String pdfText = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        assertThat(pdfText).startsWith("%PDF-1.4");
        assertThat(pdfText).contains("END-OF-DAY \\(EOD\\) RECONCILIATION REPORT");
        assertThat(pdfText).contains("%%EOF");
    }

    @Test
    @DisplayName("DIG-92: Handles multi-page pagination when transaction count exceeds single-page capacity")
    void shouldGenerateMultiPagePdf() {
        List<StatementItem> items = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            items.add(StatementItem.builder()
                    .dateTime(LocalDateTime.now().minusHours(i))
                    .referenceId("txn-bulk-" + i)
                    .txnType("TRANSFER")
                    .description("Bulk transfer item #" + i)
                    .debitAmount(new BigDecimal("50.00"))
                    .creditAmount(BigDecimal.ZERO)
                    .status("COMMITTED")
                    .build());
        }

        StatementDocumentModel model = StatementDocumentModel.builder()
                .documentTitle("BULK ACCOUNT STATEMENT")
                .accountNumber(ACCOUNT_ID)
                .customerId(CUSTOMER_ID)
                .items(items)
                .transactionCount(items.size())
                .build();

        byte[] pdfBytes = statementPdfGenerator.generateStatementPdf(model);

        assertThat(pdfBytes).isNotNull();
        String pdfText = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        assertThat(pdfText).startsWith("%PDF-1.4");
        assertThat(pdfText).contains("/Count 2"); // 2 pages created
        assertThat(pdfText).contains("Page 1 of 2");
        assertThat(pdfText).contains("Page 2 of 2");
        assertThat(pdfText).contains("%%EOF");
    }
}
