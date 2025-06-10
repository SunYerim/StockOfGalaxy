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
        throws IOException, ExecutionException, InterruptedException {
        // json메시지 (구독 성공) 처리
        if (isJsonMessage(payload)) {
            JSONObject jsonResponse = new JSONObject(payload);
            // PINGPONG 메시지 처리
            if (jsonResponse.getJSONObject("header").getString("tr_id").equals("PINGPONG")) {
                log.info("PINGPONG 메시지 수신, 연결 유지 중...");
                // 모든 구독자에게 PINGPONG 메시지를 전송
                for (Set<WebSocketSession> subscribers : stockCodeSubscribers.values()) {
                    for (WebSocketSession clientSession : subscribers) {
                        if (clientSession.isOpen()) {
                            clientSession.sendMessage(new TextMessage(payload));
                        }
                    }
                }
                return; // 연결 상태 유지 메시지이므로 여기서 처리 끝
            }

            String msgCd = jsonResponse.getJSONObject("body").getString("msg_cd");
            if (msgCd.equals("OPSP0002")) {
                log.warn("이미 해당 주식에 대해 구독 중입니다. 메시지 코드: {}", msgCd);
                return; // 추가 작업 없이 종료
            }

            // "OPSP000"이 아닌 경우, 키 재발급 요청 및 재연결
            if (!msgCd.equals("OPSP0000")) {
                log.warn("유효하지 않은 승인 키. 새로운 키를 요청합니다. 메시지 코드: {}", msgCd);
                kisChartWebSocketKeyService.requestNewWebSocketKey();
                kisWebSocketApprovalKey = kisChartWebSocketKeyService.getRealTimeWebSocketKey();

                // 재발급 받은 키로 다시 연결 시도
                if (kisWebSocketApprovalKey != null && !kisWebSocketApprovalKey.isEmpty()) {
                    log.info("새로운 키로 WebSocket을 다시 연결합니다.");
                    connectToKisWebSocket(); // WebSocket 재연결

                    // 재연결 후 기존 구독자들에 대해 다시 구독 요청 보내기
                    for (String stockCode : stockCodeSubscribers.keySet()) {
                        Set<WebSocketSession> subscribers = stockCodeSubscribers.get(stockCode);

                        // subscribers가 null이 아니고, 크기가 0이 아닐때만 실행
                        if (subscribers != null && !subscribers.isEmpty()) {

                            // 복사본 사용으로 ConcurrnetModificationException 방지
                            Set<WebSocketSession> subscribersCopy = new HashSet<>(subscribers);

                            for (WebSocketSession clientSession : subscribersCopy) {
                                if (clientSession.isOpen()) {
                                    log.info("주식 코드 {}에 대한 구독 요청을 다시 시도합니다.", stockCode);
                                    subscribeToStock(stockCode, clientSession, true); // 구독 재요청
                                }
                            }
                        }
//                        // Iterator 사용하여 안전하게 반복
//                        Iterator<WebSocketSession> iterator = subscribers.iterator();
//                        while (iterator.hasNext()) {
//                            WebSocketSession session = iterator.next();
//                            log.info("재발급 후 주식 코드 {}에 대한 구독 요청을 다시 시도합니다.", stockCode);
//                            subscribeToStock(stockCode, session, true); // 다시 구독 요청
//                        }
                    }
                } else {
                    log.error("키 재발급에 실패했습니다.");
                    throw new IllegalStateException("WebSocket 키 재발급 실패");
                }
                return; // 재발급 요청 후 처리 종료
            }
            // 구독 성공 메시지 처리
            if (jsonResponse.getJSONObject("body").getString("msg1").equals("SUBSCRIBE SUCCESS")) {
                log.info("주식 구독 성공: {}",
                    jsonResponse.getJSONObject("header").getString("tr_key"));
                return;
            }
        }

        // 실시간 데이터 처리
        ChartRealtimeResponseDTO chartRealtimeResponseDTO = parseStockResponse(payload);
        if (chartRealtimeResponseDTO != null) {
            // 해당 종목을 구독한 모든 클라이언트 세션에 실시간 데이터를 전송
            String stockCode = chartRealtimeResponseDTO.getStockCode();
            Set<WebSocketSession> subscribers = stockCodeSubscribers.get(stockCode);

            // 구독자가 있을 경우
            if (subscribers != null) {
                Set<WebSocketSession> closedSessions = new HashSet<>(); // 닫힌 세션 저장

                // Iterator 사용하여 세션 안전하게 반복
                Iterator<WebSocketSession> iterator = subscribers.iterator();
                while (iterator.hasNext()) {
                    WebSocketSession clientSession = iterator.next();
                    if (clientSession.isOpen()) {
//                        log.info("클라이언트에 데이터 전송: {}", stockCode);
                        clientSession.sendMessage(new TextMessage(
                            new ObjectMapper().writeValueAsString(chartRealtimeResponseDTO)));
                    } else {
                        log.warn("클라이언트 세션이 닫혀있습니다. 세션을 제거합니다: {}", stockCode);
                        closedSessions.add(clientSession); // 닫힌 세션을 리스트에 추가
                    }
                }

                // 닫힌 세션을 구독자 리스트에서 제거
                subscribers.removeAll(closedSessions);
                if (subscribers.isEmpty()) {
                    stockCodeSubscribers.remove(stockCode); // 구독자가 없으면 리스트에서 제거
                }
            }
        }
    }

    // 클라이언트가 새로운 종목을 구독할 때 호출되는 메서드
    public void subscribeToStock(String stockCode, WebSocketSession clientSession,
        boolean forceReSubscribe)
        throws InterruptedException, ExecutionException, IOException {
        if (kisWebSocketSession == null || !kisWebSocketSession.isOpen()) {
            connectToKisWebSocket();
        }

        // 구독 세션 관리
        Set<WebSocketSession> subscribers = stockCodeSubscribers.computeIfAbsent(stockCode,
            k -> new HashSet<>());

        if (!forceReSubscribe && subscribers != null && subscribers.contains(clientSession)) {
            log.info("이미 해당 주식을 구독하고 있습니다: {}", stockCode);
            return; // 이미 구독 중인 종목이라면 아무 작업도 하지 않음
        }

        // 종목별로 구독하는 세션 추가
        subscribers.add(clientSession);

        // 세션별 구독 종목 관리
        sessionStockMap.computeIfAbsent(clientSession, s -> new HashSet<>()).add(stockCode);

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
        header.put("approval_key", kisWebSocketApprovalKey);
        header.put("custtype", "P");
        header.put("tr_type", "1");
        header.put("content-type", "utf-8");

        Map<String, Map<String, String>> body = new HashMap<>();
        Map<String, String> input = new HashMap<>();
        input.put("tr_id", "H0STCNT0");
        input.put("tr_key", stockCode);
        body.put("input", input);

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
    public void disconnectFromKisWebSocket(WebSocketSession session) throws IOException {
        if (session != null) {
            Set<String> subscribedStocks = sessionStockMap.remove(session);

//            String stockCode = sessionStockMap.remove(session); // 해당 세션이 구독 중인 주식 코드 제거
            if (subscribedStocks != null) {
                for (String stockCode : subscribedStocks) {
                    Set<WebSocketSession> subscribers = stockCodeSubscribers.get(stockCode);
                    if (subscribers != null) {
                        subscribers.remove(session);
                        if (subscribers.isEmpty()) {
                            stockCodeSubscribers.remove(stockCode);
                            log.info("종목 {}에 대한 구독자가 모두 해제되었습니다.", stockCode);
                        }
                    }
                }
//                List<WebSocketSession> subscribers = stockCodeSubscribers.get(stockCode);
//                subscribers.remove(session);
//                if (subscribers.isEmpty()) {
//                    stockCodeSubscribers.remove(stockCode); // 구독자가 없으면 리스트에서 제거
//                }
            }
        }

        // 모든 세션이 해제된 경우 KIS WebSocket 연결 해제
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