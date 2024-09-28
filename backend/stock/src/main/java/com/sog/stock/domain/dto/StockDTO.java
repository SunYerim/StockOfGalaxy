package com.sog.stock.domain.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StockDTO {

    private String stockCode;
    private String companyName;
    private String companyDescription;
    private int establishedYear;
    private String ceo;
    private String webSite;
    private int fiscalMonth;
    private boolean isDelisted;

}