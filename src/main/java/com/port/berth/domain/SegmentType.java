package com.port.berth.domain;

public enum SegmentType {
    PILOTAGE("引航"),
    TUG_INBOUND("拖轮协助靠泊"),
    BERTHING("靠泊操纵"),
    CARGO_OP("装卸作业"),
    TUG_OUTBOUND("拖轮协助离泊"),
    UNBERTHING("离泊/解缆");

    private final String label;
    SegmentType(String label) { this.label = label; }
    public String getLabel() { return label; }
}
