package com.justjava.humanresource.payroll.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;


@Value
@Builder
public class SalaryImpactKpiSnapshotDTO {

    Long kpiId;
    String kpiCode;
    String kpiName;
    String kpiUnit;
    BigDecimal targetValue;
    BigDecimal actualValue;


    BigDecimal score;


    String snapshotSource;
}