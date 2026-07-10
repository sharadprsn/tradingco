package com.kite.trading.service;

import com.kite.trading.dto.Position;
import com.kite.trading.dto.PositionsResponse;
import com.kite.trading.exception.KiteApiException;
import com.kite.trading.exception.KiteAuthenticationException;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Implementation of PositionService for fetching trading positions.
 *
 * <p>This service handles position-related operations with Zerodha Kite API, following the Single
 * Responsibility Principle by focusing only on position management.
 *
 * @author Kite Trading Team
 * @version 1.0.0
 */
@Service
public class ZerodhaPositionService implements PositionService {

  private static final Logger logger = LoggerFactory.getLogger(ZerodhaPositionService.class);
  private static final String NIFTY_SYMBOL = "NIFTY";
  private static final String INTRADAY_PRODUCT = "MIS";

  private final ZerodhaApiClient apiClient;
  private final KiteAuthService authService;

  /**
   * Constructs ZerodhaPositionService with required dependencies.
   *
   * @param apiClient The Zerodha API client for making API calls
   * @param authService The authentication service for session management
   */
  public ZerodhaPositionService(
      final ZerodhaApiClient apiClient, final KiteAuthService authService) {
    this.apiClient = apiClient;
    this.authService = authService;
  }

  /** {@inheritDoc} */
  @Override
  public List<Position> getAllPositions() {
    logger.info("Fetching all positions");
    final PositionsResponse response = fetchPositionsFromApi();
    return combinePositions(response);
  }

  /** {@inheritDoc} */
  @Override
  public List<Position> getNiftyIntradayPositions() {
    logger.info("Fetching NIFTY intraday positions");
    final PositionsResponse response = fetchPositionsFromApi();
    final List<Position> dayPositions =
        response.data().day() != null ? response.data().day() : List.of();

    return dayPositions.stream()
        .filter(position -> isNiftyInstrument(position.tradingSymbol()))
        .filter(position -> isIntradayProduct(position.product()))
        .collect(Collectors.toList());
  }

  /** {@inheritDoc} */
  @Override
  public List<Position> getNetPositions() {
    logger.info("Fetching net positions");
    final PositionsResponse response = fetchPositionsFromApi();
    return response.data().net() != null ? response.data().net() : List.of();
  }

  /** {@inheritDoc} */
  @Override
  public List<Position> getDayPositions() {
    logger.info("Fetching day positions");
    final PositionsResponse response = fetchPositionsFromApi();
    return response.data().day() != null ? response.data().day() : List.of();
  }

  /**
   * Fetches positions from the Zerodha API.
   *
   * @return PositionsResponse containing all position data
   * @throws KiteAuthenticationException if not authenticated
   * @throws KiteApiException if the API call fails
   */
  private PositionsResponse fetchPositionsFromApi() {
    if (!authService.isAuthenticated()) {
      throw new KiteAuthenticationException("Not authenticated. Please login first.");
    }

    final String accessToken = authService.getCurrentSession().accessToken();
    return apiClient.getPositions(accessToken);
  }

  /**
   * Combines net and day positions into a single list, removing duplicates.
   *
   * @param response The positions response from API
   * @return Combined list of unique positions
   */
  private List<Position> combinePositions(final PositionsResponse response) {
    final List<Position> netPositions =
        response.data().net() != null ? response.data().net() : List.of();
    final List<Position> dayPositions =
        response.data().day() != null ? response.data().day() : List.of();

    return dayPositions.stream()
        .filter(
            dayPos ->
                netPositions.stream()
                    .noneMatch(
                        netPos ->
                            netPos.tradingSymbol().equals(dayPos.tradingSymbol())
                                && netPos.exchange().equals(dayPos.exchange())))
        .collect(Collectors.toList());
  }

  /**
   * Checks if the trading symbol contains NIFTY.
   *
   * @param symbol The trading symbol to check
   * @return true if symbol contains NIFTY, false otherwise
   */
  private boolean isNiftyInstrument(final String symbol) {
    return symbol != null && symbol.toUpperCase().contains(NIFTY_SYMBOL);
  }

  /**
   * Checks if the product type is for intraday trading.
   *
   * @param product The product type to check
   * @return true if product is MIS (intraday), false otherwise
   */
  private boolean isIntradayProduct(final String product) {
    return INTRADAY_PRODUCT.equals(product);
  }
}
