package com.sog.stock.domain.dto.websocket;

import lombok.Data;

@Data
public class StockPriceResponseDTO {
    // 현재가, 전일대비, 전일대비율, 전일대비부호

    private String stockCode; // 종목번호 - 0
    private String stockPrpr; // 현재가 - 2
    private String prdyVrssSign; // 전일 대비 부호 - 3
    private String prdyVrss; // 전일대비 - 4
    private String prdyCtrt; // 전일대비율 - 5

}
