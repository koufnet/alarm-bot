package cn.zzq0324.alarm.bot.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * description: 任务类型 <br>
 * date: 2022/2/18 3:21 下午 <br>
 * author: zzq0324 <br>
 * version: 1.0 <br>
 */
public enum TaskType {

    DOWNLOAD_CHAT_MESSAGE(1, "下载群聊消息");

    @EnumValue
    private int value;
    private String desc;

    TaskType(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    public int getValue() {
        return this.value;
    }
}
