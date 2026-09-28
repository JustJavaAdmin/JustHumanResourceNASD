package com.justjava.humanresource.hr.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class EmployeePayItemUploadDTO {

    private int rowNumber;
    private String email;
    private String itemType;
    private String itemCode;
    private BigDecimal overrideAmount;
}