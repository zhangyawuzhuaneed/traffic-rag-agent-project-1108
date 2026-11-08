package com.example.tunnel.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 的「手」：让 LLM 自主决定何时查实时数据。
 *
 * <p><b>与 RAG 的分工（面试高频）：</b>
 * <ul>
 *   <li>RAG 查的是<b>静态知识</b>：预案怎么写、阈值是多少、规则怎么定 —— 存在向量库里</li>
 *   <li>Tool 查的是<b>动态事实</b>：此刻这个桩号 CO 多少、当前是否封路 —— 来自业务系统</li>
 * </ul>
 * 把实时数据塞进向量库是典型反模式：数据刚入库就过期，还会污染检索。
 *
 * <p>本类用内存模拟数据，生产只需把方法体换成对监控中心 API / 数据库的调用，
 * LLM 侧代码一行都不用改 —— 这就是 Tool 抽象的价值。
 *
 * <p><b>工具描述就是提示词。</b> description 写错或写得含糊，LLM 会「该调时不调、
 * 不该调时乱调」。改工具逻辑后务必同步改描述，这比改实现更容易被忽略。
 */
@Component
public class TunnelTools {

    private static final Logger log = LoggerFactory.getLogger(TunnelTools.class);

    private final ObjectMapper mapper;

    public TunnelTools(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record DeviceStatus(String stake, double coPpm, int visibilityMeters,
                               double windSpeed, int fanBearingTemp,
                               String fanStatus, String sampleTime) {
    }

    private static final Map<String, DeviceStatus> SAMPLES = Map.of(
            "K12+300", new DeviceStatus("K12+300", 78.0, 180, 6.2, 62, "RUNNING_80", "2026-11-08 20:55:00"),
            "K14+100", new DeviceStatus("K14+100", 18.0, 800, 4.1, 48, "RUNNING_30", "2026-11-08 20:55:00"),
            "K10+500", new DeviceStatus("K10+500", 24.0, 90, 11.3, 55, "RUNNING_60", "2026-11-08 20:55:00")
    );

    /**
     * 查询指定桩号的实时环境与设备状态。当用户问「现在」「当前」「实时」的指标、设备状态，
     * 或需要判断某个具体桩号该采取什么措施时，必须调用本工具。
     */
    @Tool(description = "查询指定桩号隧道的实时环境指标与设备运行状态，"
            + "返回 CO 浓度(ppm)、能见度(米)、纵向风速(m/s)、风机轴承温度(℃)、风机状态与采样时间。"
            + "当用户询问当前/实时状态，或需要依据实时数值判断处置级别时调用。")
    public String queryDeviceStatus(
            @ToolParam(description = "桩号，格式为 K数字+数字，例如 K12+300、K14+100") String stake) {
        log.info("[TOOL] queryDeviceStatus({})", stake);
        DeviceStatus status = SAMPLES.get(stake);
        if (status == null) {
            return toJson(Map.of("error", "未找到桩号 " + stake + " 的实时数据",
                    "availableStakes", SAMPLES.keySet()));
        }
        return toJson(status);
    }

    /**
     * 依据指标值判定响应级别。数值来自知识库规则，这里只是把规则做成可调用的计算。
     */
    @Tool(description = "根据监控指标数值判定应急响应级别与对应的交通管制措施。"
            + "metric 传 CO 时 value 为 ppm；metric 传 VISIBILITY 时 value 为能见度米数。")
    public String lookupResponseLevel(
            @ToolParam(description = "指标名：CO 或 VISIBILITY") String metric,
            @ToolParam(description = "指标数值：CO 用 ppm，能见度用米") double value) {
        log.info("[TOOL] lookupResponseLevel({}, {})", metric, value);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("metric", metric);
        r.put("value", value);
        if ("CO".equalsIgnoreCase(metric)) {
            if (value >= 100) {
                r.put("level", "I 级（红色）");
                r.put("action", "双向全封闭，启动应急疏散，禁止人员进入");
            } else if (value >= 70) {
                r.put("level", "II 级（橙色）");
                r.put("action", "关闭隧道入口、限制通行，情报板提示通风异常");
            } else if (value >= 30) {
                r.put("level", "III 级（黄色）");
                r.put("action", "加大通风，风机提速至 80%，监控中心弹窗告警");
            } else {
                r.put("level", "正常");
                r.put("action", "维持常态监测");
            }
        } else if ("VISIBILITY".equalsIgnoreCase(metric)) {
            if (value < 50) {
                r.put("level", "I 级（红色）");
                r.put("action", "关闭入口，已驶入车辆引导至服务区，限速 20 km/h");
            } else if (value < 100) {
                r.put("level", "III 级（黄色）");
                r.put("action", "限速 40 km/h，就近驶离，间隔放行");
            } else if (value < 200) {
                r.put("level", "III 级（黄色）");
                r.put("action", "限速 60 km/h，保持 100 米车距，禁止超车");
            } else {
                r.put("level", "正常 / IV 级关注");
                r.put("action", "能见度 200~500 米时限速 80 km/h 并开启雾灯");
            }
        } else {
            r.put("error", "不支持的指标：" + metric + "，仅支持 CO 与 VISIBILITY");
        }
        return toJson(r);
    }

    /** 查询路段当前交通管制状态（模拟调度系统）。 */
    @Tool(description = "查询指定桩号路段当前生效的交通管制状态，包括限速值、封闭情况与发布渠道。"
            + "用户询问『现在封路了吗』『限速多少』时调用，不要凭常识回答。")
    public String queryTrafficControl(
            @ToolParam(description = "桩号，例如 K12+300") String stake) {
        log.info("[TOOL] queryTrafficControl({})", stake);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("stake", stake);
        r.put("publishedBy", "路段监控中心");
        r.put("publishedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        if ("K12+300".equals(stake)) {
            r.put("control", "入口限制通行");
            r.put("speedLimitKmh", 40);
            r.put("reason", "CO 浓度达二级阈值，按预案限制通行");
        } else if ("K10+500".equals(stake)) {
            r.put("control", "恶劣天气间隔放行");
            r.put("speedLimitKmh", 60);
            r.put("reason", "能见度 90 米");
        } else {
            r.put("control", "常态通行");
            r.put("speedLimitKmh", 80);
            r.put("reason", "无生效管制");
        }
        return toJson(r);
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具结果序列化失败", e);
        }
    }
}
