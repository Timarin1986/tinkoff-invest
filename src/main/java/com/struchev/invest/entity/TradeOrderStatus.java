
package com.struchev.invest.entity;

/**
 * Состояние торговой заявки.
 */
public enum TradeOrderStatus {

    // Заявка создана и сохранена в базе
    CREATED,

    // Начата отправка заявки брокеру
    SUBMITTING,

    // Заявка принята брокером
    NEW,

    // Заявка исполнена частично
    PARTIALLY_FILLED,

    // Заявка исполнена полностью
    FILLED,

    // Заявка отменена
    CANCELLED,

    // Заявка отклонена брокером
    REJECTED,

    // Состояние заявки неизвестно
    UNKNOWN
}
