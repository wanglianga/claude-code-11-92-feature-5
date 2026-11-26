package com.port.berth.domain;

public enum ResourceType {
    BERTH("泊位"), PILOT("引航员"), TUG("拖轮"), CRANE("岸桥");
    private final String label;
    ResourceType(String label) { this.label = label; }
    public String getLabel() { return label; }
}
