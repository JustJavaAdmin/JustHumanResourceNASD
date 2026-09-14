package com.justjava.humanresource.utils;

import com.justjava.humanresource.payroll.entity.PaySlipDTO;
import com.justjava.humanresource.payroll.entity.PaySlipLineDTO;
import com.justjava.humanresource.payroll.dto.SalaryImpactKpiSnapshotDTO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class PaySlipPdfService {
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    public byte[] generate(PaySlipDTO paySlip) {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Renderer renderer = new Renderer(document);
            renderer.logo(paySlip.getCompanyLogoData());
            if (paySlip.getJobGradeName() != null && !paySlip.getJobGradeName().isBlank()) {
                renderer.jobGrade(paySlip.getJobGradeName());
            }
            renderer.title("Payslip - " + month(paySlip.getPayDate()));
            renderer.text("Employee Name: " + value(paySlip.getEmployeeName()));
            renderer.text("Employee ID: " + value(paySlip.getEmployeeId()));
            renderer.text("Pay Date: " + date(paySlip.getPayDate()));
            renderer.text("Payroll Period: " + period(paySlip.getPayDate()));
            renderer.text("Pension Scheme: " + value(paySlip.getAppliedPensionSchemeName()));
            renderer.text("Bank: " + bank(paySlip));
            renderer.gap(10);

            List<SalaryImpactKpiSnapshotDTO> salaryImpactKpis = paySlip.getSalaryImpactKpis();
            if (salaryImpactKpis != null && !salaryImpactKpis.isEmpty()) {
                renderer.section("Salary KPI");
                renderer.tableHeader("KPI", "Score");
                for (SalaryImpactKpiSnapshotDTO kpi : salaryImpactKpis) {
                    renderer.tableRow(value(kpi.getKpiName()), percent(kpi.getScore()));
                }
                if (salaryImpactKpis.size() > 1 && paySlip.getSalaryKpiScore() != null) {
                    renderer.tableRow("Applied salary KPI score", percent(paySlip.getSalaryKpiScore()));
                }
                renderer.text("This payslip was calculated using the salary-impact KPI score.");
                renderer.gap(8);
            }

            renderer.section("Pension Contributions");
            renderer.tableHeader("Description", "Amount");
            renderer.tableRow("Employee Pension", money(paySlip.getPensionAmount()));
            renderer.tableRow("Employer Pension", money(paySlip.getEmployerPensionAmount()));
            renderer.gap(8);

            renderer.section("Allowances");
            renderer.tableHeader("Description", "Amount");
            List<PaySlipLineDTO> allowances = paySlip.getAllowances();
            if (allowances == null || allowances.isEmpty()) {
                renderer.text("No allowances.");
            } else {
                for (PaySlipLineDTO line : allowances) {
                    if (isResidual(line)) {
                        continue;
                    }
                    renderer.tableRow(value(line.getDescription()), money(line.getAmount()));
                }
            }
            renderer.gap(8);

            renderer.section("Deductions");
            renderer.tableHeader("Description", "Amount");
            List<PaySlipLineDTO> deductions = paySlip.getDeductions();
            if (deductions == null || deductions.isEmpty()) {
                renderer.text("No deductions.");
            } else {
                for (PaySlipLineDTO line : deductions) {
                    renderer.tableRow(value(line.getDescription()), "-" + money(line.getAmount()));
                }
            }
            renderer.gap(10);

            renderer.section("Totals");
//            renderer.tableRow("Basic Salary", money(paySlip.getBasicSalary()));
            renderer.tableRow("Gross Pay", money(paySlip.getGrossPay()));
            renderer.tableRow("Total Deductions", "-" + money(paySlip.getTotalDeductions()));
            renderer.tableRow("Net Pay", money(paySlip.getNetPay()));

            renderer.close();
            document.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not generate payslip PDF.", e);
        }
    }

    private static boolean isResidual(PaySlipLineDTO line) {
        return line != null
                && ("RESIDUAL".equalsIgnoreCase(line.getCode())
                || "Residual Adjustment".equalsIgnoreCase(line.getDescription()));
    }

    private static String bank(PaySlipDTO paySlip) {
        if (paySlip.getBankName() == null || paySlip.getBankName().isBlank()) {
            return "No bank details on file";
        }
        if (paySlip.getBankAccountNumber() == null || paySlip.getBankAccountNumber().isBlank()) {
            return paySlip.getBankName();
        }
        return paySlip.getBankName() + " - " + paySlip.getBankAccountNumber();
    }

    private static String period(LocalDate date) {
        if (date == null) {
            return "N/A";
        }
        return date.withDayOfMonth(1).format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH))
                + " - "
                + date.withDayOfMonth(date.lengthOfMonth()).format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH));
    }

    private static String month(LocalDate date) {
        return date != null ? date.format(MONTH) : "Current Period";
    }

    private static String date(LocalDate date) {
        return date != null ? date.format(FULL_DATE) : "N/A";
    }

    private static String money(BigDecimal amount) {
        BigDecimal safe = amount != null ? amount : BigDecimal.ZERO;
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return "NGN " + format.format(safe);
    }

    private static String percent(BigDecimal value) {
        BigDecimal safe = value != null ? value : BigDecimal.ZERO;
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(safe) + "%";
    }

    private static String value(Object value) {
        if (value == null) {
            return "N/A";
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? "N/A" : text;
    }

    private static String pdfText(String text) {
        String normalized = Normalizer.normalize(value(text), Normalizer.Form.NFKD);
        return normalized.replaceAll("[^\\x20-\\x7E]", "").replaceAll("\\s+", " ").trim();
    }

    private static final class Renderer {
        private static final float MARGIN = 48;
        private static final float LEADING = 15;
        private static final float TABLE_AMOUNT_X = 405;
        private final PDDocument document;
        private PDPageContentStream content;
        private float y;

        Renderer(PDDocument document) throws IOException {
            this.document = document;
            addPage();
        }

        void logo(byte[] data) throws IOException {
            if (data == null || data.length == 0) {
                return;
            }
            PDImageXObject image;
            try {
                image = PDImageXObject.createFromByteArray(document, data, "logo");
            } catch (IOException e) {
                // Corrupt/unreadable logo data shouldn't block the whole payslip.
                return;
            }

            float maxWidth = 140;
            float maxHeight = 60;
            float scale = Math.min(maxWidth / image.getWidth(), maxHeight / image.getHeight());
            scale = Math.min(scale, 1f);
            float drawWidth = image.getWidth() * scale;
            float drawHeight = image.getHeight() * scale;

            ensureSpace(drawHeight);
            float x = (PDRectangle.A4.getWidth() - drawWidth) / 2;
            content.drawImage(image, x, y - drawHeight, drawWidth, drawHeight);
            y -= drawHeight + 14;
        }

        void jobGrade(String text) throws IOException {
            ensureSpace(24);
            content.beginText();
            content.setFont(PDType1Font.HELVETICA_BOLD, 18);
            content.newLineAtOffset(MARGIN, y);
            content.showText(pdfText(text));
            content.endText();
            y -= 24;
        }

        void title(String text) throws IOException {
            ensureSpace(32);
            content.beginText();
            content.setFont(PDType1Font.HELVETICA_BOLD, 18);
            content.newLineAtOffset(MARGIN, y);
            content.showText(pdfText(text));
            content.endText();
            y -= 26;
            line();
            y -= 16;
        }

        void section(String text) throws IOException {
            ensureSpace(26);
            content.beginText();
            content.setFont(PDType1Font.HELVETICA_BOLD, 12);
            content.newLineAtOffset(MARGIN, y);
            content.showText(pdfText(text));
            content.endText();
            y -= 18;
        }

        void text(String text) throws IOException {
            ensureSpace(LEADING);
            content.beginText();
            content.setFont(PDType1Font.HELVETICA, 10);
            content.newLineAtOffset(MARGIN, y);
            content.showText(pdfText(text));
            content.endText();
            y -= LEADING;
        }

        void tableHeader(String left, String right) throws IOException {
            ensureSpace(22);
            content.beginText();
            content.setFont(PDType1Font.HELVETICA_BOLD, 9);
            content.newLineAtOffset(MARGIN, y);
            content.showText(pdfText(left));
            content.newLineAtOffset(TABLE_AMOUNT_X - MARGIN, 0);
            content.showText(pdfText(right));
            content.endText();
            y -= 12;
            line();
            y -= 8;
        }

        void tableRow(String left, String right) throws IOException {
            List<String> wrapped = wrap(pdfText(left), 58);
            ensureSpace(Math.max(1, wrapped.size()) * LEADING);
            float rowTop = y;
            for (String line : wrapped) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 10);
                content.newLineAtOffset(MARGIN, y);
                content.showText(line);
                content.endText();
                y -= LEADING;
            }
            content.beginText();
            content.setFont(PDType1Font.COURIER, 10);
            content.newLineAtOffset(TABLE_AMOUNT_X, rowTop);
            content.showText(pdfText(right));
            content.endText();
        }

        void gap(float amount) throws IOException {
            ensureSpace(amount);
            y -= amount;
        }

        void close() throws IOException {
            if (content != null) {
                content.close();
            }
        }

        private void addPage() throws IOException {
            if (content != null) {
                content.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = page.getMediaBox().getHeight() - MARGIN;
        }

        private void ensureSpace(float needed) throws IOException {
            if (y - needed < MARGIN) {
                addPage();
            }
        }

        private void line() throws IOException {
            content.moveTo(MARGIN, y);
            content.lineTo(PDRectangle.A4.getWidth() - MARGIN, y);
            content.stroke();
        }

        private static List<String> wrap(String text, int width) {
            if (text.length() <= width) {
                return List.of(text);
            }
            java.util.ArrayList<String> lines = new java.util.ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : text.split(" ")) {
                if (current.length() + word.length() + 1 > width) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                if (!current.isEmpty()) {
                    current.append(' ');
                }
                current.append(word);
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
            }
            return lines;
        }
    }
}