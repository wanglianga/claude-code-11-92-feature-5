package com.port.berth.domain;

/**船次业务状态*/
public enum VoyageStatus {
    SUBMITTED,      // 已提交
    ETA_UPDATED,    // 已补报/修订 ETA
    WITHDRAWN,      // 船代撤回授权
    CONFIRMED       // 已有确认计划
}
