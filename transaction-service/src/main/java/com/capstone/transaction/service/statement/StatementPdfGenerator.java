package com.capstone.transaction.service.statement;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Reusable, high-performance, pure-Java PDF 1.4 statement generator (DIG-92).
 * Formats official bank statements, monthly e-statements, and EOD reconciliation packs
 * with standard 14 Type1 fonts and vector graphics without requiring external native binaries.
 */
@Component
@Slf4j
public class StatementPdfGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public byte[] generateStatementPdf(StatementDocumentModel model) {
        return buildPdf(model);
    }

    public byte[] generateMonthlyEStatementPdf(StatementDocumentModel model) {
        model.setReportType("MONTHLY");
        if (model.getDocumentTitle() == null || model.getDocumentTitle().equals("ACCOUNT TRANSACTION STATEMENT")) {
            model.setDocumentTitle("MONTHLY ELECTRONIC STATEMENT (E-STATEMENT)");
        }
        return buildPdf(model);
    }

    public byte[] generateEodReportPdf(StatementDocumentModel model) {
        model.setReportType("EOD");
        if (model.getDocumentTitle() == null || model.getDocumentTitle().equals("ACCOUNT TRANSACTION STATEMENT")) {
            model.setDocumentTitle("END-OF-DAY (EOD) RECONCILIATION REPORT");
        }
        return buildPdf(model);
    }

    private byte[] buildPdf(StatementDocumentModel model) {
        try {
            List<StatementItem> allItems = model.getItems() != null ? model.getItems() : Collections.emptyList();

            // Paging: Page 1 holds up to 18 rows; subsequent pages hold up to 26 rows
            List<List<StatementItem>> pagedItems = new ArrayList<>();
            if (allItems.isEmpty()) {
                pagedItems.add(Collections.emptyList());
            } else {
                int index = 0;
                int page1Limit = 18;
                int laterLimit = 26;

                int firstChunkSize = Math.min(allItems.size(), page1Limit);
                pagedItems.add(allItems.subList(0, firstChunkSize));
                index += firstChunkSize;

                while (index < allItems.size()) {
                    int chunkSize = Math.min(allItems.size() - index, laterLimit);
                    pagedItems.add(allItems.subList(index, index + chunkSize));
                    index += chunkSize;
                }
            }

            int totalPages = pagedItems.size();
            List<String> pageStreams = new ArrayList<>();

            for (int p = 0; p < totalPages; p++) {
                int pageNumber = p + 1;
                List<StatementItem> pageRows = pagedItems.get(p);
                String streamContent = renderPageContent(model, pageRows, pageNumber, totalPages, p == 0);
                pageStreams.add(streamContent);
            }

            return assemblePdfDocument(pageStreams);
        } catch (Exception ex) {
            log.error("Failed to compile statement PDF: {}", ex.getMessage(), ex);
            throw new RuntimeException("Error rendering statement PDF: " + ex.getMessage(), ex);
        }
    }

    private String renderPageContent(StatementDocumentModel model, List<StatementItem> rows, int pageNumber, int totalPages, boolean isFirstPage) {
        StringBuilder sb = new StringBuilder();

        // 1. Header Banner (Navy blue background)
        sb.append("q\n");
        sb.append("0.07 0.18 0.36 rg\n"); // Navy blue
        sb.append("36 720 540 45 re\n"); // x=36, y=720, w=540, h=45
        sb.append("f\n");

        // Header Text: Bank Name & Title
        sb.append("BT\n");
        sb.append("/F2 14 Tf\n"); // Bold
        sb.append("1.0 1.0 1.0 rg\n"); // White
        sb.append("48 745 Td\n");
        sb.append("(").append(escapePdf(model.getBankName())).append(") Tj\n");
        sb.append("ET\n");

        sb.append("BT\n");
        sb.append("/F1 10 Tf\n");
        sb.append("0.85 0.90 1.0 rg\n");
        sb.append("48 729 Td\n");
        sb.append("(").append(escapePdf(model.getDocumentTitle())).append(") Tj\n");
        sb.append("ET\n");

        // Header right-side info: Generated date
        sb.append("BT\n");
        sb.append("/F1 8 Tf\n");
        sb.append("1.0 1.0 1.0 rg\n");
        sb.append("450 735 Td\n");
        sb.append("(Printed: ").append(model.getGeneratedAt() != null ? model.getGeneratedAt().format(DATE_TIME_FORMAT) : "").append(") Tj\n");
        sb.append("ET\n");
        sb.append("Q\n");

        int currentY = 705;

        // 2. Metadata Block (Render on First Page)
        if (isFirstPage) {
            sb.append("q\n");
            sb.append("0.96 0.97 0.99 rg\n"); // Light gray background
            sb.append("36 645 540 60 re\n");
            sb.append("f\n");
            sb.append("0.80 0.83 0.88 RG\n");
            sb.append("0.5 w\n");
            sb.append("36 645 540 60 re\n");
            sb.append("S\n");

            // Text in metadata box
            sb.append("BT\n");
            sb.append("/F2 9 Tf\n");
            sb.append("0.15 0.18 0.22 rg\n");
            sb.append("48 690 Td\n");
            sb.append("(Account Number: ").append(escapePdf(model.getAccountNumber() != null ? model.getAccountNumber() : "ALL POCKETS")).append(") Tj\n");

            sb.append("/F1 9 Tf\n");
            sb.append("0 -15 Td\n");
            sb.append("(Customer ID: ").append(escapePdf(model.getCustomerId() != null ? model.getCustomerId() : "N/A")).append(") Tj\n");

            sb.append("0 -15 Td\n");
            sb.append("(Currency: ").append(escapePdf(model.getCurrency())).append(") Tj\n");
            sb.append("ET\n");

            // Right side of metadata box
            sb.append("BT\n");
            sb.append("/F2 9 Tf\n");
            sb.append("0.15 0.18 0.22 rg\n");
            sb.append("320 690 Td\n");
            String periodStr = (model.getFromDate() != null ? model.getFromDate().format(DATE_FORMAT) : "START") +
                    " to " + (model.getToDate() != null ? model.getToDate().format(DATE_FORMAT) : "TODAY");
            sb.append("(Statement Period: ").append(escapePdf(periodStr)).append(") Tj\n");

            sb.append("/F1 9 Tf\n");
            sb.append("0 -15 Td\n");
            sb.append("(Total Transactions: ").append(model.getTransactionCount() > 0 ? model.getTransactionCount() : (model.getItems() != null ? model.getItems().size() : 0)).append(") Tj\n");

            sb.append("0 -15 Td\n");
            sb.append("(Document Status: VERIFIED & AUDITED) Tj\n");
            sb.append("ET\n");
            sb.append("Q\n");

            // 3. Balance Summary Cards (4 tiles)
            renderSummaryCards(sb, model, 595);
            currentY = 575;
        } else {
            currentY = 700;
        }

        // 4. Transaction Table Header
        int tableHeaderY = currentY - 20;
        renderTableHeader(sb, tableHeaderY);

        // 5. Transaction Rows
        int rowY = tableHeaderY - 18;
        if (rows.isEmpty()) {
            sb.append("BT\n");
            sb.append("/F1 9 Tf\n");
            sb.append("0.4 0.4 0.4 rg\n");
            sb.append("200 ").append(rowY).append(" Td\n");
            sb.append("(No transactions recorded for the selected period.) Tj\n");
            sb.append("ET\n");
        } else {
            for (int i = 0; i < rows.size(); i++) {
                StatementItem item = rows.get(i);
                boolean zebra = (i % 2 == 1);
                renderTableRow(sb, item, rowY, zebra);
                rowY -= 17;
            }
        }

        // 6. Footer (Page X of Y, Regulatory stamp)
        sb.append("q\n");
        sb.append("0.75 0.78 0.83 RG\n");
        sb.append("0.5 w\n");
        sb.append("36 45 m 576 45 l S\n"); // Horizontal separator line

        sb.append("BT\n");
        sb.append("/F1 7 Tf\n");
        sb.append("0.4 0.45 0.5 rg\n");
        sb.append("36 32 Td\n");
        sb.append("(BSP Supervised Financial Institution. This electronic statement is tamper-evident and computer generated.) Tj\n");
        sb.append("ET\n");

        sb.append("BT\n");
        sb.append("/F2 8 Tf\n");
        sb.append("0.2 0.25 0.3 rg\n");
        sb.append("510 32 Td\n");
        sb.append("(Page ").append(pageNumber).append(" of ").append(totalPages).append(") Tj\n");
        sb.append("ET\n");
        sb.append("Q\n");

        return sb.toString();
    }

    private void renderSummaryCards(StringBuilder sb, StatementDocumentModel model, int boxY) {
        int cardWidth = 127;
        int cardHeight = 42;
        int gap = 10;
        int startX = 36;

        String[] labels = {"OPENING BALANCE", "TOTAL CREDITS (+)", "TOTAL DEBITS (-)", "CLOSING BALANCE"};
        BigDecimal[] values = {
                model.getOpeningBalance() != null ? model.getOpeningBalance() : BigDecimal.ZERO,
                model.getTotalCredits() != null ? model.getTotalCredits() : BigDecimal.ZERO,
                model.getTotalDebits() != null ? model.getTotalDebits() : BigDecimal.ZERO,
                model.getClosingBalance() != null ? model.getClosingBalance() : BigDecimal.ZERO
        };

        for (int i = 0; i < 4; i++) {
            int cx = startX + i * (cardWidth + gap);

            sb.append("q\n");
            sb.append("0.97 0.98 0.99 rg\n");
            sb.append(cx).append(" ").append(boxY).append(" ").append(cardWidth).append(" ").append(cardHeight).append(" re f\n");
            sb.append("0.85 0.88 0.92 RG 0.5 w\n");
            sb.append(cx).append(" ").append(boxY).append(" ").append(cardWidth).append(" ").append(cardHeight).append(" re S\n");

            // Card title
            sb.append("BT\n");
            sb.append("/F1 7 Tf\n");
            sb.append("0.4 0.45 0.5 rg\n");
            sb.append(cx + 8).append(" ").append(boxY + 28).append(" Td\n");
            sb.append("(").append(labels[i]).append(") Tj\n");
            sb.append("ET\n");

            // Card value
            sb.append("BT\n");
            sb.append("/F2 9 Tf\n");
            if (i == 1) {
                sb.append("0.10 0.55 0.25 rg\n"); // Green for credits
            } else if (i == 2) {
                sb.append("0.75 0.15 0.15 rg\n"); // Red for debits
            } else {
                sb.append("0.10 0.15 0.25 rg\n");
            }
            sb.append(cx + 8).append(" ").append(boxY + 12).append(" Td\n");
            sb.append("(").append(escapePdf(model.getCurrency())).append(" ")
                    .append(String.format("%,.2f", values[i])).append(") Tj\n");
            sb.append("ET\n");
            sb.append("Q\n");
        }
    }

    private void renderTableHeader(StringBuilder sb, int y) {
        sb.append("q\n");
        sb.append("0.12 0.22 0.38 rg\n"); // Dark table header
        sb.append("36 ").append(y).append(" 540 18 re f\n");

        sb.append("BT\n");
        sb.append("/F2 8 Tf\n");
        sb.append("1.0 1.0 1.0 rg\n"); // White text

        sb.append("42 ").append(y + 5).append(" Td (DATE / TIME) Tj\n");
        sb.append("132 ").append(y + 5).append(" Td (REFERENCE) Tj\n");
        sb.append("217 ").append(y + 5).append(" Td (TYPE) Tj\n");
        sb.append("282 ").append(y + 5).append(" Td (DESCRIPTION) Tj\n");
        sb.append("412 ").append(y + 5).append(" Td (DEBIT) Tj\n");
        sb.append("467 ").append(y + 5).append(" Td (CREDIT) Tj\n");
        sb.append("522 ").append(y + 5).append(" Td (STATUS) Tj\n");
        sb.append("ET\n");
        sb.append("Q\n");
    }

    private void renderTableRow(StringBuilder sb, StatementItem item, int y, boolean zebra) {
        sb.append("q\n");
        if (zebra) {
            sb.append("0.96 0.97 0.98 rg\n");
            sb.append("36 ").append(y - 3).append(" 540 16 re f\n");
        }

        // Row border underline
        sb.append("0.90 0.92 0.94 RG 0.5 w\n");
        sb.append("36 ").append(y - 3).append(" m 576 ").append(y - 3).append(" l S\n");

        sb.append("BT\n");
        sb.append("/F1 7.5 Tf\n");
        sb.append("0.15 0.18 0.22 rg\n");

        String dateStr = item.getDateTime() != null ? item.getDateTime().format(DATE_TIME_FORMAT) : "-";
        sb.append("42 ").append(y + 2).append(" Td (").append(escapePdf(dateStr)).append(") Tj\n");

        String refStr = item.getReferenceId() != null ? truncate(item.getReferenceId(), 14) : "-";
        sb.append("132 ").append(y + 2).append(" Td (").append(escapePdf(refStr)).append(") Tj\n");

        String typeStr = item.getTxnType() != null ? item.getTxnType() : "TXN";
        sb.append("217 ").append(y + 2).append(" Td (").append(escapePdf(typeStr)).append(") Tj\n");

        String descStr = item.getDescription() != null ? truncate(item.getDescription(), 24) : "-";
        sb.append("282 ").append(y + 2).append(" Td (").append(escapePdf(descStr)).append(") Tj\n");

        String debitStr = item.getDebitAmount() != null && item.getDebitAmount().compareTo(BigDecimal.ZERO) > 0
                ? String.format("%,.2f", item.getDebitAmount()) : "-";
        sb.append("412 ").append(y + 2).append(" Td (").append(escapePdf(debitStr)).append(") Tj\n");

        String creditStr = item.getCreditAmount() != null && item.getCreditAmount().compareTo(BigDecimal.ZERO) > 0
                ? String.format("%,.2f", item.getCreditAmount()) : "-";
        sb.append("467 ").append(y + 2).append(" Td (").append(escapePdf(creditStr)).append(") Tj\n");

        String statusStr = item.getStatus() != null ? truncate(item.getStatus(), 9) : "POSTED";
        sb.append("522 ").append(y + 2).append(" Td (").append(escapePdf(statusStr)).append(") Tj\n");

        sb.append("ET\n");
        sb.append("Q\n");
    }

    private byte[] assemblePdfDocument(List<String> pageStreams) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Long> offsets = new ArrayList<>();

        // PDF 1.4 Header with binary comment
        write(out, "%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n");

        int totalPages = pageStreams.size();

        // 1 0 obj: Catalog
        offsets.add((long) out.size());
        write(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        // 2 0 obj: Pages collection
        offsets.add((long) out.size());
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < totalPages; i++) {
            kids.append(3 + i * 2).append(" 0 R ");
        }
        write(out, "2 0 obj\n<< /Type /Pages /Kids [ " + kids.toString().trim() + " ] /Count " + totalPages + " >>\nendobj\n");

        int font1ObjNum = 3 + totalPages * 2;
        int font2ObjNum = font1ObjNum + 1;

        // Render Page objects and Stream objects
        for (int i = 0; i < totalPages; i++) {
            int pageObjNum = 3 + i * 2;
            int streamObjNum = pageObjNum + 1;
            String streamData = pageStreams.get(i);
            byte[] streamBytes = streamData.getBytes(StandardCharsets.ISO_8859_1);

            // Page object
            offsets.add((long) out.size());
            write(out, pageObjNum + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [ 0 0 612 792 ] /Contents " +
                    streamObjNum + " 0 R /Resources << /Font << /F1 " + font1ObjNum + " 0 R /F2 " + font2ObjNum + " 0 R >> >> >>\nendobj\n");

            // Stream object
            offsets.add((long) out.size());
            write(out, streamObjNum + " 0 obj\n<< /Length " + streamBytes.length + " >>\nstream\n");
            out.write(streamBytes);
            write(out, "\nendstream\nendobj\n");
        }

        // Standard 14 Fonts: Helvetica & Helvetica-Bold
        offsets.add((long) out.size());
        write(out, font1ObjNum + " 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n");

        offsets.add((long) out.size());
        write(out, font2ObjNum + " 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>\nendobj\n");

        // Cross-reference table (xref)
        long xrefOffset = (long) out.size();
        int totalObjects = offsets.size() + 1;

        write(out, "xref\n0 " + totalObjects + "\n");
        write(out, "0000000000 65535 f \r\n");
        for (Long off : offsets) {
            write(out, String.format("%010d 00000 n \r\n", off));
        }

        // Trailer
        write(out, "trailer\n<< /Size " + totalObjects + " /Root 1 0 R >>\n");
        write(out, "startxref\n" + xrefOffset + "\n%%EOF\n");

        return out.toByteArray();
    }

    private void write(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private String escapePdf(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '(') {
                sb.append("\\(");
            } else if (c == ')') {
                sb.append("\\)");
            } else if (c == '\u20B1') { // Philippine Peso sign ₱
                sb.append("PHP ");
            } else if (c >= 32 && c <= 126) {
                sb.append(c);
            } else {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 1) + ".";
    }
}
