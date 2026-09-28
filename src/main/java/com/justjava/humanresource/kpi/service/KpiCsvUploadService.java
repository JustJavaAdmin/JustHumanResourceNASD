package com.justjava.humanresource.kpi.service;

import com.justjava.humanresource.kpi.entity.KpiDefinition;
import com.justjava.humanresource.kpi.entity.KpiMeasurement;
import com.justjava.humanresource.kpi.entity.KpiMeasurementResponseDTO;
import com.justjava.humanresource.kpi.repositories.KpiDefinitionRepository;
import com.justjava.humanresource.kpi.repositories.KpiMeasurementRepository;
import com.justjava.humanresource.dispatcher.PayrollMessageDispatcher;
import com.justjava.humanresource.hr.entity.Employee;
import com.justjava.humanresource.hr.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses a two-column CSV (email, actualValue) for a fixed KPI + period
 * chosen on the UI, and upserts each row:
 *   – if a measurement already exists  → update it (same logic as updateMeasurement)
 *   – if no measurement exists yet     → create it  (same logic as recordBulkMeasurements)
 *
 * Expected CSV format (header row is skipped):
 *   email,actualValue
 *   jane.doe@justjava.com,85.00
 *   john.smith@justjava.com,92.50
 */
@Service
@RequiredArgsConstructor
@Transactional
public class KpiCsvUploadService {

    private final KpiMeasurementRepository measurementRepository;
    private final KpiDefinitionRepository   kpiRepository;
    private final EmployeeRepository        employeeRepository;
    private final PayrollMessageDispatcher  payrollMessageDispatcher;

    /* -------------------------------------------------------
       PUBLIC ENTRY POINT
       ------------------------------------------------------- */

    public KpiCsvUploadResultDTO uploadCsv(
            MultipartFile file,
            Long          kpiId,
            YearMonth     period
    ) {
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("CSV file must not be empty.");
        if (kpiId == null)
            throw new IllegalArgumentException("KPI ID is required.");
        if (period == null)
            throw new IllegalArgumentException("Period is required.");

        KpiDefinition kpi = kpiRepository.findById(kpiId)
                .orElseThrow(() -> new IllegalArgumentException("KPI not found: " + kpiId));

        List<CsvRow>  rows    = parseCsv(file);
        List<String>  errors  = new ArrayList<>();
        List<KpiMeasurementResponseDTO> saved = new ArrayList<>();

        for (CsvRow row : rows) {

            try {
                Optional<Employee> empOpt = employeeRepository.findByEmail(row.email);
                if (empOpt.isEmpty()) {
                    errors.add("Row " + row.lineNumber + ": employee email " + row.email + " not found – skipped.");
                    continue;
                }

                Employee employee = empOpt.get();

                KpiMeasurementResponseDTO result =
                        upsertMeasurement(employee, kpi, row.actualValue, period);

                saved.add(result);

            } catch (Exception ex) {
                errors.add("Row " + row.lineNumber + ": " + ex.getMessage());
            }
        }

        return new KpiCsvUploadResultDTO(saved.size(), rows.size(), errors);
    }

    /* -------------------------------------------------------
       UPSERT – mirrors updateMeasurement() for existing records
                and recordBulkMeasurements() for new ones
       ------------------------------------------------------- */

    private KpiMeasurementResponseDTO upsertMeasurement(
            Employee      employee,
            KpiDefinition kpi,
            BigDecimal    actualValue,
            YearMonth     period
    ) {
        validateActualValue(actualValue);

        // Look for an existing record for this employee + kpi + period
        Optional<KpiMeasurement> existing =
                measurementRepository.findByEmployee_IdAndKpi_IdAndPeriod(
                        employee.getId(), kpi.getId(), period
                );

        BigDecimal score = calculateScore(actualValue, kpi.getTargetValue());

        KpiMeasurement measurement;

        if (existing.isPresent()) {
            // --- OVERRIDE (same as updateMeasurement) ---
            measurement = existing.get();
            measurement.setActualValue(actualValue);
            measurement.setScore(score);
            measurement.setRecordedAt(LocalDateTime.now());
            measurement = measurementRepository.save(measurement);

        } else {
            // --- CREATE NEW ---
            measurement = measurementRepository.save(
                    KpiMeasurement.builder()
                            .employee(employee)
                            .kpi(kpi)
                            .actualValue(actualValue)
                            .score(score)
                            .period(period)
                            .recordedAt(LocalDateTime.now())
                            .build()
            );
        }

        // Re-trigger payroll recalculation (same as both existing flows)
        payrollMessageDispatcher.requestPayroll(employee.getId(), LocalDate.now());

        return KpiMeasurementResponseDTO.builder()
                .measurementId(measurement.getId())
                .kpiId(kpi.getId())
                .kpiCode(kpi.getCode())
                .kpiName(kpi.getName())
                .actualValue(measurement.getActualValue())
                .score(measurement.getScore())
                .period(measurement.getPeriod())
                .employee(employee)
                .build();
    }

    /* -------------------------------------------------------
       CSV PARSING  (header row skipped, just like CsvParserService)
       ------------------------------------------------------- */

    private List<CsvRow> parseCsv(MultipartFile file) {

        List<CsvRow> rows = new ArrayList<>();

        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(file.getInputStream()))) {

            String  line;
            boolean skipHeader = true;
            int     lineNumber = 1;

            while ((line = reader.readLine()) != null) {

                lineNumber++;

                if (skipHeader) {
                    skipHeader = false;
                    continue;
                }

                line = line.trim();
                if (line.isEmpty()) continue;   // ignore blank lines

                String[] parts = line.split(",");

                if (parts.length < 2) {
                    throw new IllegalStateException(
                            "Line " + lineNumber + " does not have 2 columns (email, actualValue)."
                    );
                }

                String email = parts[0].trim();
                if (email.isEmpty() || !email.contains("@")) {
                    throw new IllegalStateException(
                            "Line " + lineNumber + " has an invalid email address: '" + email + "'."
                    );
                }
                if (!email.equals(email.toLowerCase())) {
                    throw new IllegalStateException(
                            "Line " + lineNumber + ": email must be lowercase: '" + email + "'."
                    );
                }

                BigDecimal actualValue = new BigDecimal(parts[1].trim());

                rows.add(new CsvRow(lineNumber, email, actualValue));
            }

        } catch (IllegalStateException ex) {
            throw ex;   // re-throw parse-level errors as-is
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("CSV contains a non-numeric actual value: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read CSV file: " + ex.getMessage(), ex);
        }

        if (rows.isEmpty()) {
            throw new IllegalStateException("CSV file has no data rows (only a header or is empty).");
        }

        return rows;
    }

    /* -------------------------------------------------------
       VALIDATION / CALCULATION  (mirrors KpiMeasurementService)
       ------------------------------------------------------- */

    private void validateActualValue(BigDecimal actualValue) {
        if (actualValue == null || actualValue.compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("Actual value must be zero or positive.");
    }

    private BigDecimal calculateScore(BigDecimal actual, BigDecimal target) {
        if (target == null || target.compareTo(BigDecimal.ZERO) <= 0)
            return BigDecimal.ZERO;

        return actual
                .divide(target, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .min(BigDecimal.valueOf(100));
    }

    /* -------------------------------------------------------
       INNER TYPES
       ------------------------------------------------------- */

    private record CsvRow(int lineNumber, String email, BigDecimal actualValue) {}
}