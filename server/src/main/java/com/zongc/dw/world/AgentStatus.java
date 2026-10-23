package com.zongc.dw.world;

/** 智能体状态。出租车在 CRUISING → TO_PICKUP → BOARDING → TO_DROPOFF → ALIGHTING → CRUISING 之间循环。 */
public enum AgentStatus {
    CRUISING,    // 空车巡游，可接单
    TO_PICKUP,   // 前往上车点
    BOARDING,    // 乘客上车中
    TO_DROPOFF,  // 载客前往目的地
    ALIGHTING,   // 乘客下车中
    FREE         // 自由移动的智能体（手机/行人），不参与派单
}
