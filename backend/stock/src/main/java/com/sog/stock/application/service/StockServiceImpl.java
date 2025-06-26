package com.sog.stock.application.service;

import com.sog.stock.application.client.KisMinuteChartClient;
import com.sog.stock.application.client.KisPresentPriceClient;
import com.sog.stock.application.client.NewsClient;
import com.sog.stock.application.client.UserClient;
import com.sog.stock.application.service.kis.KisTokenService;
import com.sog.stock.domain.dto.FinancialDTO;
import com.sog.stock.domain.dto.FinancialListDTO;
import com.sog.stock.domain.dto.HolidayAddListRequestDTO;
import com.sog.stock.domain.dto.HolidayAddRequestDTO;
import com.sog.stock.domain.dto.MinuteStockPriceDTO;
import com.sog.stock.domain.dto.MinuteStockPriceListDTO;
import com.sog.stock.domain.dto.QuarterStockPriceDTO;
import com.sog.stock.domain.dto.QuarterStockPriceListDTO;
import com.sog.stock.domain.dto.StockFrequencyByDateListDTO;
import com.sog.stock.domain.dto.StockPresentPriceResponseDTO;
import com.sog.stock.domain.dto.StockTop8ListResponseDTO;
import com.sog.stock.domain.dto.StockTop8ResponseDTO;
import com.sog.stock.domain.dto.TimeMachineListResponseDTO;
import com.sog.stock.domain.dto.TimeMachineResponseDTO;
import com.sog.stock.domain.dto.kis.KisMinuteStockResponseDTO;
import com.sog.stock.domain.dto.kis.KisPresentPriceResponseDTO;
import com.sog.stock.domain.dto.news.StockNewsCountResponseDTO;
import com.sog.stock.domain.dto.rocket.RocketAddRequestDTO;
import com.sog.stock.domain.dto.StockAddListRequestDTO;
import com.sog.stock.domain.dto.StockDTO;
import com.sog.stock.domain.dto.DailyStockPriceListDTO;
import com.sog.stock.domain.dto.DailyStockPriceDTO;
import com.sog.stock.domain.dto.StockNameResponseDTO;
import com.sog.stock.domain.dto.rocket.RocketResponseDTO;
import com.sog.stock.domain.dto.rocket.RocketResponseListDTO;
import com.sog.stock.domain.enums.QuarterType;
import com.sog.stock.domain.model.DailyStockHistory;
import com.sog.stock.domain.model.FinancialStatements;
import com.sog.stock.domain.model.QuarterStockHistory;
import com.sog.stock.domain.model.Rocket;
import com.sog.stock.domain.model.Stock;
import com.sog.stock.domain.model.StockHoliday;
import com.sog.stock.domain.repository.DailyStockHistoryRepository;
import com.sog.stock.domain.repository.FinancialStatementsRepository;
import com.sog.stock.domain.repository.QuarterStockHistoryRepository;
import com.sog.stock.domain.repository.RocketRepository;
import com.sog.stock.domain.repository.StockHolidayRepository;
import com.sog.stock.domain.repository.StockRepository;
import jakarta.transaction.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockServiceImpl implements StockService {

    private final DailyStockHistoryRepository dailyStockHistoryRepository;
    private final QuarterStockHistoryRepository quarterStockHistoryRepository;
    private final StockHolidayRepository stockHolidayRepository;
    private final StockRepository stockRepository;
    private final RocketRepository rocketRepository;
    private final FinancialStatementsRepository financialStatementsRepository;

    private final KisTokenService kisTokenService;
    private final KisPresentPriceClient kisPresentPriceClient;
    private final KisMinuteChartClient kisMinuteChartClient;
    private final UserClient userClient;
    private final NewsClient newsClient;

    @Override
    public DailyStockPriceListDTO getDailyStockHistory(String stockCode) {

        // 종목번호로 모든 데이터 조회
        List<DailyStockHistory> historyList = dailyStockHistoryRepository.findTop90ByStock_StockCodeOrderByDailyStockHistoryDateDesc(
            stockCode);

        // entity 리스트 -> dto리스트 변환
        List<DailyStockPriceDTO> dtoList = historyList.stream()
            .map(DailyStockPriceDTO::fromEntity)
            .collect(Collectors.toList());

        // DTO 리스트를 감싸서 반환
        return new DailyStockPriceListDTO(dtoList);
    }

    @Override
    public DailyStockPriceDTO getDailyStockPriceHistory(String stockCode, String locDate) {
        // 요청받은 날짜가 공휴일이거나 일요일인지 확인
        while (isHoliday(locDate)) {
            // 공휴일 또는 일요일이면 날짜를 하루 전으로 변경
            LocalDate localDateObj = LocalDate.parse(locDate,
                DateTimeFormatter.ofPattern("yyyyMMdd"));
            localDateObj = localDateObj.minusDays(1); // 하루 전 날짜로 이동
            locDate = localDateObj.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        }

        // 해당하는 영업일에 대한 주식 데이터 조회
        DailyStockHistory dailyStockHistory = dailyStockHistoryRepository
            .findByStock_StockCodeAndDailyStockHistoryDate(stockCode, locDate)
            .orElseThrow(() -> new NoSuchElementException("해당 날짜에 대한 주식 데이터를 찾을 수 없습니다."));

        // 조회된 데이터를 DTO로 변환하여 반환
        return DailyStockPriceDTO.fromEntity(dailyStockHistory);
    }

    @Override
    public void addDailyStockHistory(DailyStockPriceListDTO stockDailyPriceList) {
        List<DailyStockPriceDTO> dtoList = stockDailyPriceList.getStockDailyPriceList();

        // 각 DTO를 Entity로 변환하여 저장
        List<DailyStockHistory> entityList = dtoList.stream()
            .map(dto -> {
                // stockCode로 Stock 엔티티 조회
                Stock stock = stockRepository.findById(dto.getStockCode())
                    .orElseThrow(() -> new IllegalArgumentException(
                        "해당 종목 코드가 존재하지 않습니다: " + dto.getStockCode()));

                // DTO를 Entity로 변환 (Stock 객체와 연결)
                return dto.toEntity(stock);
            })
            .collect(Collectors.toList());

        // 변환된 엔티티를 저장
        dailyStockHistoryRepository.saveAll(entityList);
    }

    @Override
    public void addQuarterStockHistory(QuarterStockPriceListDTO quarterStockPriceList) {
        // 각 QuarterStockPriceDTO를 entity로 변환 후 저장
        List<QuarterStockPriceDTO> quarterStocklist = quarterStockPriceList.getQuarterStockPriceList();

        List<QuarterStockHistory> entityList = quarterStocklist.stream()
            .map(dto -> {
                // stockCode로 Stock 엔티티 조회
                Stock stock = stockRepository.findById(dto.getStockCode())
                    .orElseThrow((() -> new IllegalArgumentException(
                        "해당 종목 코드가 존재하지 않습니다: " + dto.getStockCode())));
                return dto.toEntity(stock);

            })
            .collect(Collectors.toList());

        quarterStockHistoryRepository.saveAll(entityList);
    }

    @Override
    public QuarterStockPriceListDTO getQuarterStockHistory(String stockCode,
        QuarterType quarterType) {
        List<QuarterStockHistory> historyList;

        // quarterType에 따라 다르게 처리
        switch (quarterType) {
            case D:
                // "D" 타입일 경우 최근 90개의 데이터를 반환
                historyList = quarterStockHistoryRepository.findTop90ByStockCodeAndQuarterType(
                    stockCode);
                break;
            case M:
                // "M" 타입일 경우 최근 60개의 데이터를 반환
                historyList = quarterStockHistoryRepository.findTop60ByStockCodeAndQuarterType(
                    stockCode);
                break;
            case Y:
                // "Y" 타입일 경우 해당 종목코드의 모든 데이터를 반환
                historyList = quarterStockHistoryRepository.findByStockCodeAndQuarterType(
                    stockCode);
                break;
            default:
                throw new IllegalArgumentException("Invalid quarter type: " + quarterType);
        }

        // 데이터가 비어 있는 경우 처리
        if (historyList == null || historyList.isEmpty()) {
            throw new NoSuchElementException(
                "No stock history found for the given stock code and quarter type.");
        }

        // List<QuarterStockHistory> -> List<QuarterStockPriceDTO> 변환
        List<QuarterStockPriceDTO> stockPriceDTOList = historyList.stream()
            .map(QuarterStockPriceDTO::fromEntity) // fromEntity 메서드를 사용하여 변환
            .collect(Collectors.toList());

        // DTO 리스트를 담은 QuarterStockPriceListDTO 생성
        QuarterStockPriceListDTO stockPriceListDTO = new QuarterStockPriceListDTO();
        stockPriceListDTO.setQuarterStockPriceList(stockPriceDTOList); // 변환된 리스트 설정

        return stockPriceListDTO; // DTO 리스트 반환
    }


    @Override
    public void addStockList(StockAddListRequestDTO stockAddListRequestDTO) {
        // 각 stockDTO를 stock entity로 변환후 저장
        for (StockDTO addStock : stockAddListRequestDTO.getStocks()) {
            Stock stock = Stock.fromDTO(addStock);
            stockRepository.save(stock);
        }
    }

    @Override
    public void addStock(StockDTO stockAddRequest) {
        Stock stock = Stock.fromDTO(stockAddRequest);
        stockRepository.save(stock);
    }

    @Override
    public StockDTO searchStock(String stockCode) {
        Stock stock = stockRepository.findById(stockCode)
            .orElseThrow(() -> new RuntimeException("행성을 찾을 수 없습니다."));

        return stock.toDTO();
    }

    @Override
    public void addHolidayList(HolidayAddListRequestDTO holidayAddListRequestDTO) {
        // 각 holiday DTO를 StockHoliday 엔티티로 변환 후 저장
        for (HolidayAddRequestDTO holidayAddRequestDTO : holidayAddListRequestDTO.getHolidays()) {
            StockHoliday stockHoliday = StockHoliday.fromDTO(holidayAddRequestDTO);
            stockHolidayRepository.save(stockHoliday);
        }
    }

    @Override
    public boolean isHoliday(String holidayDate) {
        return stockHolidayRepository.existsByLocDate(holidayDate);
    }

    @Override
    public Optional<String> getHolidayInfo(String holidayDate) {
        Pageable pageable = PageRequest.of(0, 1); // 첫 번째 결과만 반환
        List<StockHoliday> result = stockHolidayRepository.findByLocDate(holidayDate, pageable);

        if (result.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(result.get(0).getStockDate());  // 첫 번째 결과 반환
    }


    @Override
    public void addFinancialList(FinancialListDTO financialList) {
        // 각 financial dto를 entity로 변환 후 저장
        for (FinancialDTO financialDTO : financialList.getFinancialList()) {
            // stockCode가 null인지 확인
            System.out.println(financialDTO);
            if (financialDTO.getStockCode() == null || financialDTO.getStockCode().isEmpty()) {
                throw new IllegalArgumentException("종목 코드가 null이거나 비어있습니다.");
            }
            // Stock 조회
            Stock stock = stockRepository.findById(financialDTO.getStockCode())
                .orElseThrow(() -> new IllegalArgumentException(
                    "해당 종목 코드가 존재하지 않습니다: " + financialDTO.getStockCode()));

            // DTO TO ENTITY
            FinancialStatements financialStatements = FinancialStatements.builder()
                .stacyymm(financialDTO.getStacYymm())
                .totalLiabilites(financialDTO.getTotalLiabilities())
                .totalEquity(financialDTO.getTotalEquity())
                .currentAssets(financialDTO.getCurrentAssets())
                .currentLiabilites(financialDTO.getCurrentLiabilities())
                .stock(stock)
                .build();

            financialStatementsRepository.save(financialStatements);
        }

    }

    @Override
    public FinancialListDTO searchFinancial(String stockCode) {
        // 종목번호별로 제무재표 list 조회
        // stockCode로 Stock 객체 조회
        Stock stock = stockRepository.findById(stockCode)
            .orElseThrow(() -> new IllegalArgumentException("해당 종목 코드가 존재하지 않습니다: " + stockCode));

        // Stock에 해당하는 FinancialStatements 리스트 조회
        List<FinancialStatements> financialStatementsList = financialStatementsRepository.findByStock(
            stock);

        // FinancialDTO 리스트로 변환
        List<FinancialDTO> financialDTOList = financialStatementsList.stream()
            .map(fs -> new FinancialDTO(
                fs.getStock().getStockCode(),
                fs.getStacyymm(),
                fs.getCurrentAssets(),
                fs.getCurrentLiabilites(),
                fs.getTotalLiabilites(),
                fs.getTotalEquity()
            ))
            .collect(Collectors.toList());

        // FinancialListDTO에 담아서 반환
        return new FinancialListDTO(financialDTOList);
    }

    @Override
    public StockNameResponseDTO searchStockName(String stockCode) {
        // stockCode로 db에서 검색하여 Stock 객체를 반환
        Stock stock = stockRepository.findById(stockCode)
            .orElseThrow(() -> new IllegalArgumentException("해당 종목 코드가 존재하지 않습니다: " + stockCode));

        // StockNameResponseDTO로 변환하여 반환
        return new StockNameResponseDTO(stock.getCorpName());
    }

    @Override
    public StockPresentPriceResponseDTO searchStockPresentPrice(String stockCode) {
        // kisToken redis에서
        String token = kisTokenService.getAccessToken();
        if (token == null) {
            throw new RuntimeException("kis 토큰 접근 실패");
        }

        // 한국투자증권 API에 현재가 요청
        KisPresentPriceResponseDTO response = kisPresentPriceClient.requestStockPresentPrice(
            stockCode, token);

        // 응답값을 StockPresentPriceResponseDTO로 매핑하여 반환
        return StockPresentPriceResponseDTO.builder()
            .stockCode(stockCode)
            .stckPrpr(response.getOutput().getStckPrpr())         // 현재가
            .prdyVrss(response.getOutput().getPrdyVrss())         // 전일대비
            .prdyVrssSign(response.getOutput().getPrdyVrssSign()) // 전일대비 부호
            .prdyCtrt(response.getOutput().getPrdyCtrt())         // 전일대비율
            .build();
    }

    @Override
    public MinuteStockPriceListDTO getMinuteStockPriceList(String stockCode, String time) {
        // kisToken redis에서 가져오기 (필요하면 재발급)
        String token = kisTokenService.getAccessToken();
        if (token == null) {
            throw new RuntimeException("KIS 토큰 접근 실패");
        }

        // 090000보다 크면 1번 요청, 입력된 시간이 093000보다 크면 2번 요청, 100000보다 크면 3번 요청
        List<MinuteStockPriceDTO> minutePrices = new ArrayList<>();
        LocalTime requestedTime = LocalTime.parse(time, DateTimeFormatter.ofPattern("HHmmss"));
        LocalTime openingTime = LocalTime.of(9, 0, 0);  // 장 오픈 시간
        int requestCount = 0;  // 요청 횟수

        // 시간에 따른 요청 횟수 계산
        if (requestedTime.isAfter(LocalTime.of(10, 0, 0))) {
            // 100000보다 크면 3번 요청
            requestCount = 10;
        } else if (requestedTime.isAfter(LocalTime.of(9, 30, 0))) {
            // 093000보다 크고 100000보다 작으면 2번 요청
            requestCount = 2;
        } else if (requestedTime.isAfter(openingTime)) {
            // 090000보다 크고 093000보다 작으면 1번 요청
            requestCount = 1;
        }

        // 필요한 만큼 요청 보내기
        for (int i = 0; i < requestCount; i++) {
            KisMinuteStockResponseDTO response = kisMinuteChartClient.requestMinuteChart(stockCode,
                requestedTime.format(DateTimeFormatter.ofPattern("HHmmss")), token);
            List<KisMinuteStockResponseDTO.Output2> output2List = response.getOutput2();

            output2List.forEach(output2 -> {
                MinuteStockPriceDTO dto = MinuteStockPriceDTO.builder()
                    .stckBsopDate(output2.getStckBsopDate())
                    .stckCntgHour(output2.getStckCntgHour())
                    .stckPrpr(output2.getStckPrpr())
                    .stckOprc(output2.getStckOprc())
                    .stckHgpr(output2.getStckHgpr())
                    .stckLwpr(output2.getStckLwpr())
                    .cntgVol(output2.getCntgVol())
                    .acmlTrPbmn(output2.getAcmlTrPbmn())
                    .build();

                minutePrices.add(dto);
            });

            // 30분씩 감소시켜서 다음 요청 시간을 조정
            requestedTime = requestedTime.minusMinutes(30);
        }

        return MinuteStockPriceListDTO.builder()
            .minuteStockPrices(minutePrices)
            .build();
    }


    // 로켓 전체 조회 -> 같은 주식 코드로 연결된 로켓 리스트
    @Override
    public Mono<RocketResponseListDTO> getAllRocketsByStockCode(String stockCode) {
        Stock stock = stockRepository.findById(stockCode)
            .orElseThrow(() -> new RuntimeException("Stock not found"));

        List<Rocket> rockets = rocketRepository.findAllByStockRAndRocketIdDesc(
            stock.getStockCode());

        // Mono 리스트를 Flux로 변환하여 모두 처리
        return Flux.fromIterable(rockets)
            .concatMap(this::buildRocketResponse) // 각 rocket을 비동기적으로 처리
            .collectList() // 처리된 RocketResponseDTO 리스트를 다시 Mono로 변환
            .map(RocketResponseListDTO::new); // 리스트를 RocketResponseListDTO로 변환
    }

    // Rocket을 DTO로 변환하고 유저 정보를 포함하는 메서드
    private Mono<RocketResponseDTO> buildRocketResponse(Rocket rocket) {
        return userClient.getUserInfo(rocket.getMemberId())
            .map(userInfo -> RocketResponseDTO.builder()
                .rocketId(rocket.getRocketId())
                .memberId(rocket.getMemberId())
                .nickname(userInfo.getNickname())
                .characterType(userInfo.getCharacterType())
                .createdAt(rocket.getRocketCreatedAt())
                .message(rocket.getContent())
                .price(rocket.getStockPrice())
                .build());
    }

    // 로켓 개별 조회
    @Override
    public Mono<RocketResponseDTO> getRocketById(int rocketId) {
        log.info("Called getRocketById with rocketId: " + rocketId); // 메서드 호출 확인

        return Mono.justOrEmpty(rocketRepository.findById(rocketId))
            .doOnNext(rocket -> {
                log.info("Rocket Entity: " + rocket);
            })
            .filter(rocket -> !rocket.getIsDeleted())
            .switchIfEmpty(Mono.error(new RuntimeException("삭제되었거나 존재하지 않는 로켓입니다.")))
            .flatMap(rocket -> {
                log.info("Fetching user info for memberId: " + rocket.getMemberId());
                return userClient.getUserInfo(rocket.getMemberId())
                    .doOnNext(userInfoResponseDTO -> {
                        log.info("User Info Response: " + userInfoResponseDTO);
                    })
                    .map(userInfoResponseDTO -> {
                        log.info("Mapping RocketResponseDTO");
                        return RocketResponseDTO.builder()
                            .rocketId(rocket.getRocketId())
                            .memberId(rocket.getMemberId())
                            .nickname(userInfoResponseDTO.getNickname())
                            .characterType(userInfoResponseDTO.getCharacterType())
                            .createdAt(rocket.getRocketCreatedAt())
                            .message(rocket.getContent())
                            .price(rocket.getStockPrice())
                            .build();
                    });
            });
    }


    @Override
    public Mono<RocketResponseListDTO> getLimitedRocketsByStockCode(String stockCode, int limit) {
        Stock stock = stockRepository.findById(stockCode)
            .orElseThrow(() -> new RuntimeException("Stock not found"));

        // 삭제되지 않은 로켓을 rocketId가 큰 순서로 조회
        List<Rocket> rockets = rocketRepository.findTop7ByStock(stock.getStockCode());

        // Mono 리스트를 Flux로 변환하여 모두 처리
        return Flux.fromIterable(rockets)
            .concatMap(this::buildRocketResponse) // 각 rocket을 비동기적으로 처리
            .collectList() // 처리된 RocketResponseDTO 리스트를 다시 Mono로 변환
            .map(RocketResponseListDTO::new); // 리스트를 RocketResponseListDTO로 변환
    }


    @Override
    public boolean deleteRocket(int rocketId, Long memberId) {
        // 로켓 조회 후 memberId 확인
        return rocketRepository.findById(rocketId)
            .filter(rocket -> rocket.getMemberId().equals(memberId))  // memberId가 일치하는지 확인
            .map(rocket -> {
                rocket.markAsDeleted();  // 소프트 삭제 (isDeleted 플래그 변경)
                rocketRepository.save(rocket);  // 변경사항 저장
                return true;
            })
            .orElse(false);  // 로켓이 없거나 memberId가 일치하지 않으면 false 반환
    }


    @Override
    public void addRocket(RocketAddRequestDTO rocketAddRequestDTO) {
        // 주식 정보 조회
        Stock stock = stockRepository.findById(rocketAddRequestDTO.getStockCode())
            .orElseThrow(() -> new RuntimeException("해당 종목을 찾을 수 없습니다."));

        Rocket rocket = Rocket.createRocket(rocketAddRequestDTO, stock);

        // 엔티티 저장
        rocketRepository.save(rocket);
    }

    // 날짜별 top8 주식에 대한 기사수와 주식 데이터 -> rank, 종목번호, 종목이름을 갖고있는 객체의 리스트를 return
    @Override
    public Mono<StockTop8ListResponseDTO> getTop8StocksWithNews(String date) {
        String newsDate = convertToNewsDateFormat(date);
        return newsClient.getNewsCountResponse(newsDate)
            .flatMap(stockNewsList -> {
                // 기사 수(count)를 기준으로 내림차순 정렬
                stockNewsList.sort((a, b) -> b.getCount().compareTo(a.getCount()));

                List<StockTop8ResponseDTO> stockTop8ResponseList = new ArrayList<>();
                int rank = 1;

                // 우선순위 리스트
                List<String> priorityList = Arrays.asList("LG", "LG전자", "LG화학");

                for (StockNewsCountResponseDTO stockNews : stockNewsList) {
                    // 종목명을 통해 종목번호를 부분 일치 검색
                    List<Stock> stocks = stockRepository.findAllByExactCorpName(
                        stockNews.getStockName());

                    // 여러 종목이 검색되었을 때 우선순위에 따른 필터링 로직 적용
                    Optional<Stock> selectedStock = stocks.stream()
                        .sorted(Comparator.comparingInt(stock -> {
                            int index = priorityList.indexOf(stock.getCorpName());
                            return index == -1 ? Integer.MAX_VALUE : index; // 우선순위가 없으면 마지막 순위로
                        }))
                        .findFirst(); // 우선순위가 높은 종목을 선택

                    if (selectedStock.isPresent()) {
                        Stock stock = selectedStock.get();
                        stockTop8ResponseList.add(new StockTop8ResponseDTO(
                            rank,
                            stock.getCorpName(),
                            stock.getStockCode()
                        ));
                    } else {
                        // 매칭되지 않은 경우 기본값 추가
                        stockTop8ResponseList.add(new StockTop8ResponseDTO(
                            rank,
                            stockNews.getStockName(), // 뉴스에서 받은 종목명 사용
                            "Unknown" // 매칭되지 않은 경우 기본 종목 코드
                        ));
                    }

                    rank++;
                    // 8개의 종목만 추가
                    if (rank > 8) {
                        break;
                    }
                }

                return Mono.just(new StockTop8ListResponseDTO(stockTop8ResponseList));
            });
    }


    @Override
    @Transactional
    public Mono<TimeMachineListResponseDTO> getStockFrequencyByDate(String startDate,
        String endDate) {
        // 1. 날짜 형식을 YYYY-MM-DD로 변환
        String startFormatted = convertToNewsDateFormat(startDate);
        String endFormatted = convertToNewsDateFormat(endDate);

        // 2. 뉴스 기사 수 데이터 요청
        return newsClient.getNewsCountByDate(startFormatted, endFormatted)
            .flatMapMany(Flux::fromIterable)
            .map(newsData -> {
                String stockDateFormat = convertNewsDateToStockDate(newsData.getDate().toString());

                // 3. 공휴일 여부와 이름 조회
                Optional<String> holidayInfoOpt = getHolidayInfo(stockDateFormat);
                boolean isHoliday = holidayInfoOpt.isPresent();
                String holidayInfo = holidayInfoOpt.orElse("영업일");

                // 3. 공휴일이면 뉴스 기사수만 처리
                if (isHoliday(stockDateFormat)) {
                    return new TimeMachineResponseDTO(
                        stockDateFormat,
                        newsData.getCount(),
                        "0",
                        isHoliday,
                        holidayInfo,
                        Collections.emptyList()
                    );
                }

                Pageable pageable = PageRequest.of(0, 3); // 상위 3개

                // 4. 공휴일이 아닌 경우 DailyStockHistory에서 상위 3개 주식 조회
                List<DailyStockHistory> top3Stocks = dailyStockHistoryRepository
                    .findTop3ByVolumeByDate(stockDateFormat, pageable);  // 변환된 날짜로 상위 3개 주식 조회

                // 상위 3개 주식의 총 거래량 계산
                long totalVolume = top3Stocks.stream()
                    .mapToLong(stock -> Long.parseLong(stock.getStockAcmlVol()))
                    .sum();

                // 평균 거래량 계산
                long averageVolume = (top3Stocks.size() > 0) ? totalVolume / top3Stocks.size() : 0;

                // 상위 3개 주식명 리스트 추출
                List<String> stockNames = top3Stocks.stream()
                    .map(stock -> stock.getStock().getCorpName())
                    .distinct() // 중복제거
                    .collect(Collectors.toList());

                // 뉴스 데이터가 빈 배열일 경우 기사 수를 0으로 설정
                int newsCount = newsData.getCount() == null ? 0 : newsData.getCount();

                // 5. DTO 생성
                return new TimeMachineResponseDTO(
                    stockDateFormat,              // 변환된 날짜
                    newsCount,          // 기사 수
                    String.valueOf(averageVolume), // 평균 거래량
                    isHoliday,
                    holidayInfo,
                    stockNames                  // 상위 3개 주식명
                );
            })
            .collectList()
            .map(TimeMachineListResponseDTO::new);  // List로부터 DTO 생성
    }

    // "yyyyMMdd" -> "yyyy-MM-dd"로 변환
    public static String convertToNewsDateFormat(String date) {
        LocalDate localDate = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyyMMdd"));
        return localDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    private String convertNewsDateToStockDate(String newsDate) {
        LocalDate date = LocalDate.parse(newsDate);
        return date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

}
