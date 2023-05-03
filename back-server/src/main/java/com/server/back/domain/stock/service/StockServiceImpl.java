package com.server.back.domain.stock.service;

import com.server.back.common.code.commonCode.DealType;
import com.server.back.common.service.AuthService;
import com.server.back.domain.stock.dto.*;
import com.server.back.domain.stock.entity.*;
import com.server.back.domain.stock.repository.*;
import com.server.back.domain.user.entity.UserEntity;
import com.server.back.domain.user.repository.UserRepository;
import com.server.back.domain.user.service.UserService;
import com.server.back.exception.CustomException;
import com.server.back.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.transaction.Transactional;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Log4j2
@Service
@RequiredArgsConstructor
public class StockServiceImpl implements StockService {
    private final StockRepository stockRepository;
    private final UserDealRepository userDealRepository;
    private final ChartRepository chartRepository;
    private final UserRepository userRepository;
    private final DealStockRepository dealStockRepository;
    private final MaterialRepository materialRepository;
    private final ExchangeRepository exchangeRepository;
    private final AuthService authService;
    private final UserService userService;
    SseEmitter emitter;

    public SseEmitter subscribe(){
        // SSE 구독
        emitter =  new SseEmitter();

        // SSE 연결이 종료될 때 리스트에서 해당 emitter를 삭제
        emitter.onCompletion(() -> {
            emitter.complete();
        });
        emitter.onTimeout(() -> {
            emitter.complete();
        });

        // 연결
        try {
            emitter.send(SseEmitter.event()
                    .name("connect")
                    .data("connected!"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return emitter;
    }
    @Override
    public StockInfoResDto getStockList() {
        List<StockEntity> stockList = stockRepository.findTop4ByOrderByIdDesc();
        List<MaterialEntity> oil = materialRepository.findAllByStandardTypeAndDateBetween("유가", stockList.get(0).getMarket().getStartAt() , stockList.get(0).getMarket().getEndAt());
        List<MaterialEntity> gold = materialRepository.findAllByStandardTypeAndDateBetween("금", stockList.get(0).getMarket().getStartAt() , stockList.get(0).getMarket().getEndAt());
        List<ExchangeEntity> usd = exchangeRepository.findAllByNationalCodeAndDateBetween("미국", stockList.get(0).getMarket().getStartAt() , stockList.get(0).getMarket().getEndAt());
        List<ExchangeEntity> jyp = exchangeRepository.findAllByNationalCodeAndDateBetween("일본", stockList.get(0).getMarket().getStartAt() , stockList.get(0).getMarket().getEndAt());
        List<ExchangeEntity> euro = exchangeRepository.findAllByNationalCodeAndDateBetween("유럽 연합", stockList.get(0).getMarket().getStartAt() , stockList.get(0).getMarket().getEndAt());

        return StockInfoResDto.fromEntity(stockList, oil,gold, usd, jyp, euro);
    }

    @Override
    public void getStockChart(Long stockId) {
    // 로그인한 유저 가져오기
    Long userId = authService.getUserId();

    // 장 정보 가져오기
    StockEntity stock = stockRepository.findById(stockId).get();
    LocalDate startDate = stock.getMarket().getStartAt();
    LocalDate gameDate = stock.getMarket().getGameDate();

    Long companyId = stock.getCompany().getId();

    // 주식 chart
    List<ChartEntity> stockChartList = chartRepository.findAllByCompanyIdAndDateBetween(companyId, startDate, gameDate);

    // 유저 보유 주식
    Optional<UserDealEntity> optUserDeal = userDealRepository.findByUserIdAndStockId(userId, stockId);

    StockResDto stockResDto = StockResDto.fromEntity(stockId,optUserDeal, stockChartList);

        try {
            emitter.send(SseEmitter.event().data(stockResDto));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
}


    @Transactional
    @Override
    public DealResDto buyStock(StockReqDto stockReqDto) {
        // 0 값을 보냈을 때 error 처리
        if(stockReqDto.getStockAmount() == 0 ){
            throw new CustomException(ErrorCode.MISMATCH_REQUEST);
        }

        // 로그인한 유저 가져오기
        Long userId = authService.getUserId();
        UserEntity user = userService.getUserById(userId);
        StockEntity stock = stockRepository.findById(stockReqDto.getStockId()).get();


        // 종가 가져오기
        ChartEntity chart = chartRepository.findByCompanyIdAndDate(stock.getCompany().getId(), stock.getMarket().getGameDate())
                .orElseThrow(() -> new CustomException(ErrorCode.ENTITY_NOT_FOUND));
        Long chartPrice = chart.getPriceEnd();

        // 변화율 * 종가
        Optional<ChartEntity> change = chartRepository.findById(chart.getId()-1);
        if(change.isPresent() && change.get().getChangeRate() != 0){
            chartPrice = (long) (chartPrice * change.get().getChangeRate());
        }


        // 에러처리 : 돈 부족
        if(user.getCurrentMoney() < chartPrice * stockReqDto.getStockAmount()){
            throw new CustomException(ErrorCode.LACK_OF_MONEY);
        }


        // 매수
        chart.buy(stockReqDto.getStockAmount());
        chartRepository.save(chart);
        // 1. 주식 산 만큼 돈 빼내기
        user.decreaseCurrentMoney(chartPrice * stockReqDto.getStockAmount());

        userRepository.save(user);
        // 2. 거래내역 남기기
        dealStockRepository.save(stockReqDto.toEntity(user, DealType.LOSE_MONEY_FOR_STOCK, stock, chartPrice * stockReqDto.getStockAmount()));


        // 보유 주식 data 수정
        Optional<UserDealEntity> userDeal = userDealRepository.findByUserIdAndStockId(userId, stockReqDto.getStockId());
        if(userDeal.isPresent()){
            userDeal.get().increase(stockReqDto.getStockAmount() , chartPrice);
            userDealRepository.save(userDeal.get());
        }
        else {
            UserDealEntity newDeal = new UserDealEntity(user, stockReqDto, stock , chartPrice);
            userDealRepository.save(newDeal);
        }

        return DealResDto.fromEntity("매수", chartPrice, stockReqDto.getStockAmount(), stock.getCompany().getKind() );
    }
    
    

    @Transactional
    @Override
    public DealResDto sellStock(StockReqDto stockReqDto) {

        // 거래량이 0일 때 예외처리
        if(stockReqDto.getStockAmount() == 0){
            throw new CustomException(ErrorCode.MISMATCH_REQUEST);
        }

        // 로그인한 유저 가져오기
        Long userId = authService.getUserId();
        UserEntity user = userService.getUserById(userId);
        StockEntity stock = stockRepository.findById(stockReqDto.getStockId()).get();
        UserDealEntity userDeal = userDealRepository.findByUserIdAndStockId(userId, stockReqDto.getStockId()).orElseThrow(() -> new CustomException(ErrorCode.ENTITY_NOT_FOUND));

        // 종가 가져오기
        // 원본 종가
        ChartEntity chart = chartRepository.findByCompanyIdAndDate(stock.getCompany().getId(), stock.getMarket().getGameDate())
                .orElseThrow(() -> new CustomException(ErrorCode.ENTITY_NOT_FOUND));
        Long chartPrice = chart.getPriceEnd();

        // 변화율 * 종가
        Optional<ChartEntity> change = chartRepository.findById(chart.getId()-1);
        if(change.isPresent() && change.get().getChangeRate() != 0){
            chartPrice = (long) (chartPrice * change.get().getChangeRate());
        }


        // 매도
        // 1. 주식 판 만큼 돈 더하기
        if(userDeal.getTotalAmount() < stockReqDto.getStockAmount()){
            stockReqDto.setStockAmount(userDeal.getTotalAmount());
        }

        if(userDeal.getTotalAmount() == 0) {
            throw new CustomException(ErrorCode.LACK_OF_STOCK);
        }

        user.increaseCurrentMoney(chartPrice * stockReqDto.getStockAmount());
        userRepository.save(user);

        chart.sell(stockReqDto.getStockAmount());
        chartRepository.save(chart);

        // 2. 거래내역 남기기
        if(stockReqDto.getStockAmount() > 0 ){
            dealStockRepository.save(stockReqDto.toEntity(user, DealType.GET_MONEY_FOR_STOCK, stock, chartPrice * stockReqDto.getStockAmount()));
        }

        // 3. user_deal 수정
        userDeal.decrease(stockReqDto.getStockAmount(), chartPrice);
        userDealRepository.save(userDeal);

        return DealResDto.fromEntity("매도", chartPrice,  stockReqDto.getStockAmount(), stock.getCompany().getKind());
    }

    @Transactional
    // 수익률 계산하는 함수
    public void calRate(){
        // 현재 주식 종목 가져오기.
        List<StockEntity> stockList = stockRepository.findTop4ByOrderByIdDesc();

        // 종목마다 종가 들고온 후 계산
        stockList.forEach(stock -> {
            // 원본 종가
            ChartEntity chart = chartRepository.findByCompanyIdAndDate(stock.getCompany().getId(), stock.getMarket().getGameDate())
                    .orElseThrow(() -> new CustomException(ErrorCode.ENTITY_NOT_FOUND));
            Long chartPrice = chart.getPriceEnd();

            // 변화율
           Optional<ChartEntity> change = chartRepository.findById(chart.getId()-1);
           if(change.isPresent() && change.get().getChangeRate() != 0){
               chartPrice = (long) (chartPrice * change.get().getChangeRate());
           }

           System.out.println("변화율" + chartPrice);

            final Long finalChartPrice = chartPrice;
            List<UserDealEntity> usersDeal = userDealRepository.findAllStockId(stock.getId());
            usersDeal.forEach(user -> {
                user.calRate(finalChartPrice);
                userDealRepository.save(user);
            });
        });

    }
}
