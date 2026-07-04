package com.kite.trading.service;

import com.kite.trading.dto.OptionChainData;

public interface OptionChainClient {
    OptionChainData fetchOptionChain();
}
