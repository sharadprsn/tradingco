package com.kite.trading.controller;

import com.kite.trading.dto.OptionChainStrategyStatus;
import com.kite.trading.dto.StrategyStatus;
import com.kite.trading.service.NiftyFuturesStrategyService;
import com.kite.trading.service.OptionChainStrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/strategy")
public class StrategyController {

    private static final Logger logger = LoggerFactory.getLogger(StrategyController.class);

    private final NiftyFuturesStrategyService niftyFuturesStrategy;
    private final OptionChainStrategyService optionChainStrategy;

    public StrategyController(final NiftyFuturesStrategyService niftyFuturesStrategy,
                              final OptionChainStrategyService optionChainStrategy) {
        this.niftyFuturesStrategy = niftyFuturesStrategy;
        this.optionChainStrategy = optionChainStrategy;
    }

    @PostMapping("/start")
    public ResponseEntity<String> start() {
        logger.info("Manual strategy start requested");
        niftyFuturesStrategy.start();
        return ResponseEntity.ok("Strategy started");
    }

    @PostMapping("/stop")
    public ResponseEntity<String> stop() {
        logger.info("Manual strategy stop requested");
        niftyFuturesStrategy.stop();
        return ResponseEntity.ok("Strategy stopped");
    }

    @PostMapping("/evaluate")
    public ResponseEntity<String> evaluate() {
        logger.info("Manual strategy evaluation requested");
        niftyFuturesStrategy.evaluate();
        return ResponseEntity.ok("Strategy evaluated");
    }

    @GetMapping("/status")
    public ResponseEntity<StrategyStatus> status() {
        return ResponseEntity.ok(niftyFuturesStrategy.getStatus());
    }

    @PostMapping("/option-chain/start")
    public ResponseEntity<String> startOptionChain() {
        logger.info("Option chain strategy start requested");
        optionChainStrategy.start();
        return ResponseEntity.ok("Option chain strategy started");
    }

    @PostMapping("/option-chain/stop")
    public ResponseEntity<String> stopOptionChain() {
        logger.info("Option chain strategy stop requested");
        optionChainStrategy.stop();
        return ResponseEntity.ok("Option chain strategy stopped");
    }

    @PostMapping("/option-chain/evaluate")
    public ResponseEntity<String> evaluateOptionChain() {
        logger.info("Option chain strategy evaluation requested");
        optionChainStrategy.evaluate();
        return ResponseEntity.ok("Option chain strategy evaluated");
    }

    @GetMapping("/option-chain/status")
    public ResponseEntity<OptionChainStrategyStatus> statusOptionChain() {
        return ResponseEntity.ok(optionChainStrategy.getStatus());
    }
}
