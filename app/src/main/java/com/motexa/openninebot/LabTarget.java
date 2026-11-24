package com.motexa.openninebot;

/** UI workflow selection; never infers a vehicle's authentication or codec. */
enum LabTarget {
    NINEBOT_FIVE("ninebot_five", "九号 5 英寸投屏", "开启仪表投屏 → 选择 Wi-Fi 仪表 → 投屏", "udp", true),
    NINEBOT_SEVEN("ninebot_seven", "九号 7 英寸投屏", "开启仪表投屏 → 选择 Wi-Fi 仪表 → 投屏", "kcp", true),
    NINEBOT_HALF("ninebot_half", "九号半彩投屏", "资料 → 蓝牙连接 → 投屏", "ble", true),
    NINEBOT_BLUETOOTH("ninebot_bluetooth", "九号蓝牙与车辆信息", "资料 → 蓝牙连接 → 只读车辆信息", "ble", false);

    final String id, label, steps, transport;
    final boolean casting;
    LabTarget(String id, String label, String steps, String transport, boolean casting) {
        this.id = id; this.label = label; this.steps = steps; this.transport = transport; this.casting = casting;
    }
    boolean ninebot() { return true; }
    boolean wifi() { return this == NINEBOT_FIVE || this == NINEBOT_SEVEN; }
    static LabTarget fromId(String id) {
        for (LabTarget value : values()) if (value.id.equals(id)) return value;
        return NINEBOT_FIVE;
    }
}
