package com.sog.stock.application.service.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sog.stock.application.service.kis.KisChartWebSocketKeyService;
import com.sog.stock.application.service.RedisService;
import com.sog.stock.domain.dto.websocket.ChartRealtimeResponseDTO;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

@Service
@Slf4j
public class ChartWebSocketService {

    private final WebSocketClient webSocketClient;
    private final KisChartWebSocketKeyService kisChartWebSocketKeyService;
    private WebSocketSession kisWebSocketSession;
    private String kisWebSocketApprovalKey;

    // 도메인 정보와 엔드포인트 정보 추가
    private final String kisWebSocketDomain = "ws://ops.koreainvestment.com:21000";
    private final String kisWebSocketEndPoint = "/tryitout/H0STCNT0";

    // KIS 메시지 관련 상수 정의
    private static final String KIS_TR_ID_PINGPONG = "PINGPONG";
    private static final String KIS_TR_ID_STOCK_QUOTE = "H0STCNT0"; // 실시간 시세 구독 요청 TR ID
    private static final String KIS_MSG_CODE_SUBSCRIBE_SUCCESS = "OPSP0000";
    private static final String KIS_MSG_CODE_ALREADY_SUBSCRIBED = "OPSP0002";
    private static final String KIS_MSG_TEXT_SUBSCRIBE_SUCCESS = "SUBSCRIBE SUCCESS";

    // KIS 메시지 헤더와 바디에 사용되는 키 상수
    private static final String KIS_HEADER_APPROVAL_KEY = "approval_key";
    private static final String KIS_HEADER_CUST_TYPE = "custtype";
    private static final String KIS_HEADER_TR_TYPE = "tr_type";
    private static final String KIS_HEADER_CONTENT_TYPE = "content-type";
    private static final String KIS_BODY_INPUT = "input";
    private static final String KIS_INPUT_TR_ID = "tr_id";
    private static final String KIS_INPUT_TR_KEY = "tr_key";

    // 종목 코드별로 구독한 클라이언트 세션을 관리
    private final Map<String, Set<WebSocketSession>> stockCodeSubscribers = new ConcurrentHashMap<>();
    // 세션별 구독 중인 종목을 관리
    private final Map<WebSocketSession, Set<String>> sessionStockMap = new ConcurrentHashMap<>();

    @Autowired
    public ChartWebSocketService(WebSocketClient webSocketClient, RedisService redisService,
        KisChartWebSocketKeyService kisChartWebSocketKeyService) {
        this.webSocketClient = webSocketClient;
        this.kisWebSocketApprovalKey = kisChartWebSocketKeyService.getRealTimeWebSocketKey();
        this.kisChartWebSocketKeyService = kisChartWebSocketKeyService;
    }

    // KIS Websocket 연결
    public void connectToKisWebSocket() throws InterruptedException, ExecutionException {
        String kisWebSocketApprovalKey = kisChartWebSocketKeyService.getRealTimeWebSocketKey();
        if (kisWebSocketApprovalKey == null || kisWebSocketApprovalKey.isEmpty()) {
            log.error("KIS WebSocket Approval Key가 없습니다. 연결할 수 없습니다.");
            return;
        }

        if (kisWebSocketSession == null || !kisWebSocketSession.isOpen()) {
            String fullWebSocketUrl = kisWebSocketDomain + kisWebSocketEndPoint;

            // KIS WebSocket으로 연결
            webSocketClient.doHandshake(new AbstractWebSocketHandler() {
                @Override
                public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                    kisWebSocketSession = session;
                    log.info("KIS WebSocket에 연결되었습니다.");
                }

                @Override
                protected void handleTextMessage(WebSocketSession session, TextMessage message)
                    throws Exception {
                    String payload = message.getPayload();
//                    log.info("KIS WebSocket으로부터 메시지 수신: {}", payload);
                    handleRealTimeData(payload);

                }

            }, fullWebSocketUrl).get();
        }
    }

    // 실시간 데이터 처리 메서드 분리
    private void handleRealTimeData(String payload)
        throws Exception {
        // json메시지 (구독 성공) 처리
        if (isJsonMessage(payload)) {
            JSONObject jsonResponse = new JSONObject(payload);
            handleKisControlMessage(jsonResponse);
        } else {
            // 실시간 데이터 처리
            ChartRealtimeResponseDTO chartRealtimeResponseDTO = parseStockResponse(payload);
            if (chartRealtimeResponseDTO != null) {
                distributeStockPrice(chartRealtimeResponseDTO); // 실시간 데이터 배포 위임
            }
        }
    }

    private void distributeStockPrice(ChartRealtimeResponseDTO chartRealtimeResponseDTO) {
        String stockCode = chartRealtimeResponseDTO.getStockCode();
        Set<WebSocketSession> subscribers = stockCodeSubscribers.get(stockCode);

        // 구독자가 있을 경우
        if (subscribers != null) {
            Set<WebSocketSession> safeSubscribers;
            synchronized (stockCodeSubscribers) {
                Iterator<WebSocketSession> iterator = subscribers.iterator();
                while (iterator.hasNext()) {
                    WebSocketSession clientSession = iterator.next();
                    if (clientSession.isOpen()) {
                        iterator.remove();
                    }
                }
                if (subscribers.isEmpty()) {
                    stockCodeSubscribers.remove(stockCode);
                }
                safeSubscribers = new HashSet<>(subscribers);
            }

            for (WebSocketSession clientSession : safeSubscribers) {
                try {
                    if (clientSession.isOpen()) {
                        clientSession.sendMessage(new TextMessage(
                            new ObjectMapper().writeValueAsString(
                                chartRealtimeResponseDTO)));
                    }
                } catch (IOException e) {
                    log.error("클라이언트로 메시지 전송 중 에러 발생: {}", e.getMessage());
                }
            }
        }
    }

    private void handleKisControlMessage(JSONObject jsonResponse) throws Exception {
        // 1. PINGPONG 메시지 처리
        if (jsonResponse.getJSONObject("header").getString("tr_id").equals(KIS_TR_ID_PINGPONG)) {
            log.info("PINGPONG 메시지 수신, 연결 유지 중...");
            // 모든 구독자에게 PINGPONG 메시지를 전송
            synchronized (stockCodeSubscribers) {
                for (Set<WebSocketSession> subscribers : stockCodeSubscribers.values()) {
                    Iterator<WebSocketSession> iterator = subscribers.iterator();
                    while (iterator.hasNext()) {
                        WebSocketSession clientSession = iterator.next();
                        if (clientSession.isOpen()) {
                            clientSession.sendMessage(new TextMessage(jsonResponse.toString()));
                        } else {
                            iterator.remove(); // 닫힌 세션 제거
                        }
                    }
                }
            }
            return; // PINGPONG 메시지 처리 후 바로 종료
        }

        String msgCd = jsonResponse.getJSONObject("body").getString("msg_cd");

        // 2. 이미 해당 주식에 대해 구독 중인 경우 처리
        if (msgCd.equals(KIS_MSG_CODE_ALREADY_SUBSCRIBED)) {
            log.warn("이미 해당 주식에 대해 구독 중입니다. 메시지 코드: {}", msgCd);
            return; // 추가 작업 없이 종료
        }

        // 3. 승인 키가 유효하지 않거나 기타 오류인 경우 처리
        // KIS 메시지 코드가 "OPSP0000"이 아니라면 (즉, 구독 성공이 아니라면) 키 재발급 로직을 수행.
        if (!msgCd.equals(KIS_MSG_CODE_SUBSCRIBE_SUCCESS)) {
            handleInvalidApprovalKeyAndResubscribe();
            return;
        }

        // 4. 구독 성공 메시지 처리 (위의 모든 조건에 해당하지 않고, msgCd가 "OPSP0000"일 경우)
        if (jsonResponse.getJSONObject("body").getString("msg1")
            .equals(KIS_MSG_TEXT_SUBSCRIBE_SUCCESS)) {
            log.info("주식 구독 성공: {}",
                jsonResponse.getJSONObject("header").getString("tr_key"));
            return;
        }

        // 만약 위에 명시된 KIS 제어 메시지가 아니라면(일일 요청 최대 한도 도달 등)
        // 추가 로깅이나 예외 처리 가능 TO-DO
        log.warn("알 수 없는 KIS 제어 메시지 수신: {}", jsonResponse.toString());
    }

    private void handleInvalidApprovalKeyAndResubscribe() throws Exception {
        log.warn("유효하지 않은 승인 키. 새로운 키를 요청합니다. (KIS 재연결 및 재구독 시도)");
        kisChartWebSocketKeyService.requestNewWebSocketKey();
        kisWebSocketApprovalKey = kisChartWebSocketKeyService.getRealTimeWebSocketKey();

        // 재발급 받은 키로 다시 연결 시도
        if (kisWebSocketApprovalKey != null && !kisWebSocketApprovalKey.isEmpty()) {
            log.info("새로운 키로 WebSocket을 다시 연결합니다.");
            connectToKisWebSocket(); // WebSocket 재연결

            // 재연결 후 기존 구독자들에 대해 다시 구독 요청 보내기
            // 동기화로 데이터 무결성 보장
            synchronized (stockCodeSubscribers) {
                for (String stockCode : stockCodeSubscribers.keySet()) {
                    Set<WebSocketSession> subscribers = stockCodeSubscribers.get(stockCode);

                    // subscribers가 null이 아니고, 크기가 0이 아닐때만 실행
                    if (subscribers != null && !subscribers.isEmpty()) {
                        Iterator<WebSocketSession> iterator = subscribers.iterator();
                        while (iterator.hasNext()) {
                            WebSocketSession clientSession = iterator.next();
                            if (clientSession.isOpen()) {
                                log.info("주식 코드 {}에 대한 구독 요청을 다시 시도합니다.", stockCode);
                                subscribeToStock(stockCode, clientSession, true); // 구독 재요청
                            } else {
                                iterator.remove(); // 닫힌 세션 제거
                            }
                        }
                    }
                }
            }
        } else {
            log.error("키 재발급에 실패했습니다.");
            throw new IllegalStateException("WebSocket 키 재발급 실패");
        }
        return; // 재발급 요청 후 처리 종료

    }

    // 클라이언트가 새로운 종목을 구독할 때 호출되는 메서드
    public void subscribeToStock(String stockCode, WebSocketSession clientSession,
        boolean forceReSubscribe)
        throws Exception {
        if (kisWebSocketSession == null || !kisWebSocketSession.isOpen()) {
            connectToKisWebSocket();
        }

        // 구독 세션 관리
        Set<WebSocketSession> subscribers = stockCodeSubscribers.computeIfAbsent(stockCode,
            k -> ConcurrentHashMap.newKeySet());

        if (!forceReSubscribe && subscribers.contains(clientSession)) {
            log.info("이미 해당 주식을 구독하고 있습니다: {}", stockCode);
            return; // 이미 구독 중인 종목이라면 아무 작업도 하지 않음
        }

        // 종목별로 구독하는 세션 추가
        subscribers.add(clientSession);

        // 세션별 구독 종목 관리
        sessionStockMap.computeIfAbsent(clientSession, s -> ConcurrentHashMap.newKeySet())
            .add(stockCode);

        // KIS WebSocket으로 구독 요청 전송
        String requestMessage = createSubscribeMessage(stockCode);
        kisWebSocketSession.sendMessage(new TextMessage(requestMessage));
        log.info("Sent subscription request for stock: {}", stockCode);
    }

    // JSON 메시지 판별 (연결 확인 메시지 구분)
    private boolean isJsonMessage(String message) {
        try {
            new JSONObject(message);
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    // KIS 응답을 DTO로 변환하는 로직
    private ChartRealtimeResponseDTO parseStockResponse(String response) {
        // 데이터를 | 기준으로 분리
        String[] pipeSplitData = response.split("\\|");

        // 배열 크기 체크
        if (pipeSplitData.length < 2) {
            log.warn("Invalid response format: {}", response);
            return null; // 배열 크기가 예상보다 작으면 null 반환
        }

        // 마지막 요소를 ^로 분리
        String[] caretSplitData = pipeSplitData[pipeSplitData.length - 1].split("\\^");

        // 배열 크기 체크
        if (caretSplitData.length < 6) {
            log.warn("Invalid caret-split data: {}", response);
            return null; // caretSplitData 배열 크기 확인 후 데이터가 없으면 null 반환
        }

        // DTO로 매핑
        ChartRealtimeResponseDTO chartRealtimeResponseDTO = new ChartRealtimeResponseDTO();
        chartRealtimeResponseDTO.setStockCode(caretSplitData[0]);
        chartRealtimeResponseDTO.setClosePrice(caretSplitData[2]);
        chartRealtimeResponseDTO.setOpenPrice(caretSplitData[7]);
        chartRealtimeResponseDTO.setHighPrice(caretSplitData[8]);
        chartRealtimeResponseDTO.setLowPrice(caretSplitData[9]);
        chartRealtimeResponseDTO.setStockAcmlVol(caretSplitData[13]);
        chartRealtimeResponseDTO.setStockAcmlTrPbmn(caretSplitData[14]);
        chartRealtimeResponseDTO.setPrdyVrss(caretSplitData[4]);
        chartRealtimeResponseDTO.setPrdyVrssSign(caretSplitData[3]);
        chartRealtimeResponseDTO.setPrdyCtrt(caretSplitData[5]);

        return chartRealtimeResponseDTO;
    }

    // KIS WebSocket으로 주식 구독 메시지를 전송하기 위한 JSON 메시지 생성
    private String createSubscribeMessage(String stockCode) {
        Map<String, String> header = new HashMap<>();
        header.put(KIS_HEADER_APPROVAL_KEY, kisWebSocketApprovalKey);
        header.put(KIS_HEADER_CUST_TYPE, "P");
        header.put(KIS_HEADER_TR_TYPE, "1");
        header.put(KIS_HEADER_CONTENT_TYPE, "utf-8");

        Map<String, Map<String, String>> body = new HashMap<>();
        Map<String, String> input = new HashMap<>();
        input.put(KIS_INPUT_TR_ID, KIS_TR_ID_STOCK_QUOTE);
        input.put(KIS_INPUT_TR_KEY, stockCode);
        body.put(KIS_BODY_INPUT, input);

        Map<String, Object> request = new HashMap<>();
        request.put("header", header);
        request.put("body", body);

        // json 변환
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            String jsonMessage = objectMapper.writeValueAsString(request);

            // 메시지 로그 찍기
            log.info("Generated subscription message: {}", jsonMessage);

            return jsonMessage;

        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 변환 에러", e);
        }
    }

    // 클라이언트 세션 모두 종료 시 KIS Websocket도 연결 해제
    public void disconnectFromKisWebSocket(WebSocketSession session) {
        // 1. 유효하지 않거나 이미 닫힌 세션은 즉시 처리 종료
        if (session == null || !session.isOpen()) {
            return;
        }

        // 2. 해당 세션이 구독했던 모든 종목 코드 목록을 가져오고, sessionStockMap에서 세션 정보 제거
        Set<String> subscribedStocks = sessionStockMap.remove(session);

        if (subscribedStocks != null) {
            // 3. 구독했던 각 종목에 대해 반복 처리
            for (String stockCode : subscribedStocks) {
                // 4. stockCodeSubscribers에서 해당 종목의 구독자 목록을 안전하게 업데이트
                //    computeIfPresent를 사용하여 동시성 문제 방지 및 Map 값 업데이트
                stockCodeSubscribers.computeIfPresent(stockCode, (key, currentSubscribers) -> {
                    currentSubscribers.remove(session);

                    // 5. 해당 종목의 구독자 Set이 비었는지 확인

                    if (currentSubscribers.isEmpty()) {
                        log.info("종목 {}에 대한 구독자가 모두 해제되었습니다.", stockCode);
                        return null; // Set이 비었으므로 Map에서 해당 종목 엔트리 제거
                    }
                    return currentSubscribers;

                });
            }
        }

        // 6. 모든 세션이 해제된 경우 KIS WebSocket 연결 해제
        if (sessionStockMap.isEmpty() && kisWebSocketSession != null) {
            try {
                if (kisWebSocketSession.isOpen()) {
                    kisWebSocketSession.close();
                    kisWebSocketSession = null;
                    log.info("모든 클라이언트 세션 종료로 KIS WebSocket을 해제하였습니다.");
                } else {
                    kisWebSocketSession = null;
                    log.warn("KIS WebSocket이 이미 닫혀 있습니다.");
                }
            } catch (IOException e) {
                log.error("KIS WebSocket 연결 해제 중 에러 발생: {}", e.getMessage());
            }
        }
    }
}

/*
*
*
- 종목번호 [0]
- 현재가 - close_price (필드에 실시간일때는 현재가 넣어 보내기.) [2]
- 시작가 - open_price [7]
- 고가 - high_price [8]
* 저가 - low_price [9]
* 누적거래량 - stock_acml_vol [13]
* 누적거래대금 - stock_acml_tr_pbmn [14]
* 전일 대비 - prdy_vrss [4]
* 전일 대비 부호 - prdy_vrss_sign [3]
* 전일 대비율 - prdy_ctrt [5]
* */