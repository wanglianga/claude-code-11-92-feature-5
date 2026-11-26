package com.port.berth.domain;

/**方案状态*/
public enum PlanStatus {
    CANDIDATE,      // 候选（可能 feasible=false）
    STAGED,         // 已暂存，资源被 TENTATIVE 暂留
    CONFIRMED,      // 已确认，FIRM 占用，对班组生效
    EXPIRED,        // 暂留 TTL 到期释放
    CANCELLED,      // 调度/船代主动取消
    SUPERSEDED,     // 被新候选批次取代
    REPLANNED       // 扰动后被重排新版本替代（保留已执行段）
}
