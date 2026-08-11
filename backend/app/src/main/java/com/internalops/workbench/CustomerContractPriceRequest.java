package com.internalops.workbench;

import java.math.BigDecimal;

public record CustomerContractPriceRequest(Long skuId, BigDecimal salePrice) {}
