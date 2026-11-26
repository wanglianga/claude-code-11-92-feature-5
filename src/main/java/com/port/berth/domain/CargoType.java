package com.port.berth.domain;

public enum CargoType {
    CONTAINER("集装箱"),
    BULK("散货"),
    TANKER("液货/油轮"),
    RORO("滚装"),
    GENERAL("件杂货");

    private final String label;
    CargoType(String label) { this.label = label; }
    public String getLabel() { return label; }
}
