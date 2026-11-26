package com.struchev.invest.service.tinkoff;

import com.struchev.invest.entity.TradeOrderEntity;
import ru.tinkoff.piapi.contract.v1.PostOrderResponse;

public interface ITinkoffSandboxOrderAPI {

    void validateSandboxOrder(TradeOrderEntity order);

    PostOrderResponse submitSandboxOrder(TradeOrderEntity order);
}
