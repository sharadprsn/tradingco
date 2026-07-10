package com.kite.trading.service;

import com.kite.trading.dto.Position;
import java.util.List;

/**
 * Service interface for managing trading positions.
 *
 * <p>This interface defines the contract for position-related operations with the Zerodha Kite API,
 * following the Interface Segregation Principle.
 *
 * @author Kite Trading Team
 * @version 1.0.0
 */
public interface PositionService {

  /**
   * Retrieves all active positions for the authenticated user.
   *
   * <p>This method fetches both net positions and day trading positions from the Zerodha Kite API.
   *
   * @return List of all active positions
   * @throws KiteApiException if the API call fails
   */
  List<Position> getAllPositions();

  /**
   * Retrieves all NIFTY intraday positions.
   *
   * <p>This method filters positions to return only those with: - Trading symbol containing "NIFTY"
   * - Product type "MIS" (intraday)
   *
   * @return List of NIFTY intraday positions
   * @throws KiteApiException if the API call fails
   */
  List<Position> getNiftyIntradayPositions();

  /**
   * Retrieves net positions (overnight + intraday).
   *
   * @return List of net positions
   * @throws KiteApiException if the API call fails
   */
  List<Position> getNetPositions();

  /**
   * Retrieves day trading positions only.
   *
   * @return List of day trading positions
   * @throws KiteApiException if the API call fails
   */
  List<Position> getDayPositions();
}
