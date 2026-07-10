package com.kite.trading.controller;

import com.kite.trading.dto.Position;
import com.kite.trading.service.PositionService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for position management operations.
 *
 * <p>This controller provides endpoints for fetching trading positions from the Zerodha Kite API,
 * following the Single Responsibility Principle.
 *
 * @author Kite Trading Team
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/v1/positions")
public class PositionController {

  private static final Logger logger = LoggerFactory.getLogger(PositionController.class);

  private final PositionService positionService;

  /**
   * Constructs PositionController with the position service.
   *
   * @param positionService The position service for fetching positions
   */
  public PositionController(final PositionService positionService) {
    this.positionService = positionService;
  }

  /**
   * Retrieves all active positions for the authenticated user.
   *
   * <p>This endpoint returns both net positions and day trading positions combined into a single
   * list.
   *
   * @return ResponseEntity containing list of all positions
   */
  @GetMapping
  public ResponseEntity<List<Position>> getAllPositions() {
    logger.info("Fetching all positions");
    final List<Position> positions = positionService.getAllPositions();
    return ResponseEntity.ok(positions);
  }

  /**
   * Retrieves all NIFTY intraday positions.
   *
   * <p>This endpoint filters positions to return only: - Instruments with trading symbol containing
   * "NIFTY" - Positions with product type "MIS" (intraday)
   *
   * @return ResponseEntity containing list of NIFTY intraday positions
   */
  @GetMapping("/nifty/intraday")
  public ResponseEntity<List<Position>> getNiftyIntradayPositions() {
    logger.info("Fetching NIFTY intraday positions");
    final List<Position> positions = positionService.getNiftyIntradayPositions();
    return ResponseEntity.ok(positions);
  }

  /**
   * Retrieves net positions (overnight + intraday).
   *
   * @return ResponseEntity containing list of net positions
   */
  @GetMapping("/net")
  public ResponseEntity<List<Position>> getNetPositions() {
    logger.info("Fetching net positions");
    final List<Position> positions = positionService.getNetPositions();
    return ResponseEntity.ok(positions);
  }

  /**
   * Retrieves day trading positions only.
   *
   * @return ResponseEntity containing list of day positions
   */
  @GetMapping("/day")
  public ResponseEntity<List<Position>> getDayPositions() {
    logger.info("Fetching day positions");
    final List<Position> positions = positionService.getDayPositions();
    return ResponseEntity.ok(positions);
  }
}
