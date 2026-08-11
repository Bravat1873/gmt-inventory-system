package com.internalops.workbench;

import java.time.LocalDate;
import java.util.List;

public record CustomerContractRequest(String contractNo, LocalDate startDate, LocalDate endDate,
                                      String remark, List<CustomerContractPriceRequest> prices) {}
