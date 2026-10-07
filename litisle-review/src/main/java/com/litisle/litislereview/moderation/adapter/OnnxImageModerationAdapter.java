package com.litisle.litislereview.moderation.adapter;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.litisle.litislereview.enums.ModerationCategoryEnum;
import com.litisle.litislereview.enums.VerdictEnum;
import com.litisle.litislereview.moderation.config.ModerationProperties;
import com.litisle.litislereview.moderation.spi.ContentModerationService;
import com.litisle.litislereview.moderation.spi.ModerationResult;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * ONNX 本地图片机审适配器（image-provider=onnx 时激活，开发期）
 *拿到一张图片的字节流 → 本地 ONNX 模型推理 → 输出 NSFW 分数 → 按阈值判定 PASS / REJECT / SUSPICIOUS
 *OnnxImageModerationAdapter = 本地图片鉴黄引擎：图片进来 → 缩放 → ONNX 推理 → NSFW 打分 → 阈值判定，全链路本地完成，不依赖外部服务。
 *
 * <p>【模型口径 SSOT（落位模型前必须核对，TODO 核对 input card）】
 * 预处理按 nsfwjs MobileNetV2 导出约定：224×224×3、float32、NHWC、像素值 0–255
 * 不归一化；输出 5 类 [drawings, hentai, neutral, porn, sexy]，取
 * hentai+porn+sexy 三类之和为 NSFW 综合分。实际落位模型若为 MobileNetV3 或
 * 类目顺序不同，仅需调整 {@link #preprocess} / {@link #nsfwScore} 两个方法——
 * 模型换版须同步 +1 litisle.moderation.image.model-version（缓存 key 与留痕口径变更）</p>
 *
 * <p>【故障语义与 LLM 适配器不同（§2.7/§3 表 L575）】ONNX 是本地基础设施，
 * 解码失败/推理异常/超时属运维故障而非输出不可信，一律抛异常 → 消费者不 ACK →
 * 延迟插件退避重试；耗尽转人审。与 LLM 的「解析失败→SUSPICIOUS 宁疑不放」刻意不对称</p>
 *
 * <p>【并发闸】Semaphore(permits≈CPU 核数，外置)：超并发立即快速失败（不排队，
 * 防止积压放大延迟与 MQ 消费线程占用）；推理提交独立线程池执行 + future.get(timeout)
 * 封顶单次耗时。⚠️ 原生推理不响应中断，timeout 到达时 native 调用可能仍在运行，
 * 但 permits 上限 + 快速失败保证最坏情况有界，不死锁不雪崩</p>
 *
 * <p>【生命周期】模型文件在 Bean 构造期加载（provider=onnx 但文件缺失/损坏 →
 * 启动失败 fail-fast，兼作 §4.3 启动自检的一部分）</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "litisle.moderation.image-provider", havingValue = "onnx")
public class OnnxImageModerationAdapter implements ContentModerationService {


    /** 模型输入图片边长：224×224（nsfwjs MobileNetV2 导出口径，需与 preprocess 缩放尺寸一致） */
    private static final int INPUT_SIDE = 224;

    /** 模型输入通道数：RGB 三通道 */
    private static final int INPUT_CHANNELS = 3;

    /** nsfwjs 5 类输出下标：0-drawings / 1-hentai / 2-neutral / 3-porn / 4-sexy */
    private static final int IDX_HENTAI = 1;
    /** NSFW 综合分取 hentai + porn + sexy 三类之和，本行记录 porn 在输出数组中的下标 */
    private static final int IDX_PORN = 3;
    /** 同上，sexy 在输出数组中的下标 */
    private static final int IDX_SEXY = 4;

    /** ONNX 全局环境句柄（进程单例），管理底层推理运行时资源 */
    private final OrtEnvironment env;

    /** ONNX 推理会话：加载模型文件后创建，持有模型权重和计算图，线程安全可复用 */
    private final OrtSession session;

    /** 并发闸：信号量控制同时推理的线程数（≈CPU 核数），tryAcquire 快速失败不排队，防积压 */
    private final Semaphore permits;

    /** 推理专用线程池：提交推理任务 + future.get(timeout) 封顶耗时，不占用 MQ 消费线程 */
    private final ExecutorService inferExecutor;

    /** 配置属性：持有阈值、超时、并发数、模型路径等可运营参数 */
    private final ModerationProperties properties;

    /**
     * 构造器：Bean 创建时执行，负责启动自检 + 加载 ONNX 模型 + 初始化并发控制资源
     * 任何一步失败都会抛异常 → Spring Bean 创建失败 → 应用启动中断（fail-fast）
     *
     * 启动自检：校验模型路径 → 加载 ONNX 模型进内存 → 初始化并发信号量和推理线程池，失败直接让应用起不来
     */
    public OnnxImageModerationAdapter(ModerationProperties properties) throws OrtException {
        this.properties = properties;
        // 自检前置：model-path 缺失直接 fail-fast（异常消息指明缺失配置项，便于排障）
        String modelPath = properties.getOnnx().getModelPath();
        if (modelPath == null || modelPath.isBlank()) {
            throw new IllegalStateException("image-provider=onnx 但 litisle.moderation.onnx.model-path 未配置，启动失败");
        }
        // ONNX 全局环境句柄（进程单例，JVM 生命周期内复用）
        this.env = OrtEnvironment.getEnvironment();
        // 构造期加载模型：文件缺失/损坏抛 OrtException → Bean 创建失败 → 启动中断（兼作启动自检）
        this.session = env.createSession(modelPath);
        // 并发闸 permits≈CPU 核数（外置）：tryAcquire 快速失败不排队，防积压放大延迟
        this.permits = new Semaphore(properties.getOnnx().getMaxConcurrency());
        // 推理独立线程池：不占 MQ 消费线程；daemon 线程不阻断 JVM 退出
        this.inferExecutor = Executors.newFixedThreadPool(properties.getOnnx().getMaxConcurrency(), r -> {
            Thread t = new Thread(r, "onnx-infer");
            t.setDaemon(true);
            return t;
        });
        log.info("ONNX 鉴黄模型加载完成：path={}, modelVersion={}, permits={}, timeoutMs={}",
                modelPath, properties.getImage().getModelVersion(),
                properties.getOnnx().getMaxConcurrency(), properties.getOnnx().getTimeoutMs());
    }

    /**
     * 文本适配器不支持图片审核：出现本调用说明 text-provider 与已注册适配器错配，
     * fail-fast 暴露配置错误（禁止隐式放行），启动自检落地前由本异常兜底
     *
     * 不支持文本审核，调用直接抛异常（配置错配 fail-fast）
     */
    @Override
    public ModerationResult moderateText(String content) {
        throw new UnsupportedOperationException("image-provider=onnx 适配器不支持文本审核，请检查 litisle.moderation.text-provider 配置");
    }

    /**
     * 图片审核：解码 → 缩放 → 张量 →（并发闸 + 超时封顶）推理 → 阈值三分态
     * <p>全链路任何失败（解码/推理/超时/许可不足）抛异常走延迟重试，不返回降级结论</p>
     *
     * 图片审核入口：解码 → 缩放 → 推理 → 打分 → 按阈值返回 PASS/REJECT/SUSPICIOUS
     */
    @Override
    public ModerationResult moderateImage(byte[] imageBytes) {
        long start = System.currentTimeMillis();
        // 四段流水线：解码→预处理→推理→打分；任一环失败抛异常走 MQ 重试（不产生降级结论）
        BufferedImage image = decode(imageBytes);
        float[][][][] input = preprocess(image);
        float[] classScores = runInference(input);
        double nsfw = nsfwScore(classScores);
        // 阈值三分态（阈值外置可运营调整）：reason 带综合分（3 位小数），人工复审可依分值复核
        ModerationProperties.Onnx onnx = properties.getOnnx();
        if (nsfw >= onnx.getRejectThreshold()) {
            return new ModerationResult(VerdictEnum.REJECT, ModerationCategoryEnum.PORN,
                    "NSFW 综合分 " + String.format("%.3f", nsfw) + " 超过违规阈值", null, costMs(start));
        }
        if (nsfw >= onnx.getSuspiciousThreshold()) {
            return new ModerationResult(VerdictEnum.SUSPICIOUS, ModerationCategoryEnum.PORN,
                    "NSFW 综合分 " + String.format("%.3f", nsfw) + " 处于存疑区间，转人工复核", null, costMs(start));
        }
        return new ModerationResult(VerdictEnum.PASS, ModerationCategoryEnum.NONE, "正常图片", null, costMs(start));
    }

    /**
     * 字节流解码：损坏/不支持格式抛异常（运维故障语义，走重试），不做静默判定
     *
     * 把图片字节流解码成 BufferedImage，解码失败抛异常走 MQ 重试
     */
    private BufferedImage decode(byte[] imageBytes) {
        try {
            // ImageIO.read 对无法识别的格式返回 null（不抛异常），归一转异常统一重试语义
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                throw new IOException("无法识别的图片格式");
            }
            return image;
        } catch (IOException e) {
            // 解码失败属本地设施故障语义：不判定、只抛异常 → 消费者不 ACK → 延迟重试
            log.error("机审取图解码失败：{}", e.getMessage());
            throw new IllegalStateException("图片解码失败，按故障重试", e);
        }
    }

    /**
     * 预处理：双线性缩放 224×224 + RGB 三通道 float（0–255，nsfwjs 导出口径）
     * <p>TODO 核对：落位模型 input card 若要求归一化（0–1）或 NCHW 排布，调整本方法</p>
     *
     * 图片预处理：双线性缩放到 224×224 + 拆 RGB 三通道 → 转成 NHWC float 张量
     */
    private float[][][][] preprocess(BufferedImage src) {
        // 双线性缩放到 224×224；TYPE_INT_RGB 强制去 alpha（透明像素按黑底渲染）
        BufferedImage scaled = new BufferedImage(INPUT_SIDE, INPUT_SIDE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.drawImage(src, 0, 0, INPUT_SIDE, INPUT_SIDE, null);
        } finally {
            g.dispose();
        }
        // NHWC 布局 [1][y][x][c]，像素值 0–255 不归一化（nsfwjs MobileNetV2 导出口径，TODO 核对）
        float[][][][] data = new float[1][INPUT_SIDE][INPUT_SIDE][INPUT_CHANNELS];
        for (int y = 0; y < INPUT_SIDE; y++) {
            for (int x = 0; x < INPUT_SIDE; x++) {
                // INT 像素按位拆 R/G/B 三通道
                int rgb = scaled.getRGB(x, y);
                data[0][y][x][0] = (rgb >> 16) & 0xFF;
                data[0][y][x][1] = (rgb >> 8) & 0xFF;
                data[0][y][x][2] = rgb & 0xFF;
            }
        }
        return data;
    }

    /**
     * 并发闸 + 独立线程池推理 + 超时封顶；输入/输出节点名从 session 动态读取，
     * 不硬编码（不同导出工具命名不一）
     *
     * 并发闸控制 + 提交推理到线程池 + 超时封顶，返回模型输出的 5 类概率分数数组
     */
    private float[] runInference(float[][][][] input) {
        ModerationProperties.Onnx onnx = properties.getOnnx();
        // 快速失败不排队：闸满立即抛异常走重试，避免 MQ 消费线程集体阻塞在推理上
        if (!permits.tryAcquire()) {
            throw new IllegalStateException("ONNX 并发闸已满（permits=" + onnx.getMaxConcurrency() + "），快速失败走重试");
        }
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flatten(input)),
                new long[]{1, INPUT_SIDE, INPUT_SIDE, INPUT_CHANNELS})) {
            // 节点名动态读取，不硬编码（不同导出工具的 input 命名不一）
            String inputName = session.getInputNames().iterator().next();
            return inferExecutor.submit(() -> {
                try (OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
                    float[][] output = (float[][]) result.get(0).getValue();
                    return output[0];
                }
                // future.get(timeout) 封顶单次耗时；原生推理不响应中断，超时后 native 调用可能
                // 仍在运行，但 permits 上限 + 快速失败保证最坏情况有界，不死锁不雪崩
            }).get(onnx.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // 恢复中断标记（超时中断路径已置位），避免吞掉中断信号污染后续阻塞操作
            Thread.currentThread().interrupt();
            log.error("ONNX 推理失败/超时：{}", e.getMessage());
            throw new IllegalStateException("ONNX 推理故障，按重试处理", e);
        } finally {
            // 任何路径（成功/异常/超时）必须释放许可，否则闸泄漏逐渐担死吞吐
            permits.release();
        }
    }

    /**
     * 把 4 维 [1][224][224][3] 张量展平为 1 维 float 数组（ONNX 输入要求）
     * @param input
     * @return
     */
    private float[] flatten(float[][][][] input) {
        float[] flat = new float[INPUT_SIDE * INPUT_SIDE * INPUT_CHANNELS];
        int i = 0;
        for (int y = 0; y < INPUT_SIDE; y++) {
            for (int x = 0; x < INPUT_SIDE; x++) {
                for (int c = 0; c < INPUT_CHANNELS; c++) {
                    flat[i++] = input[0][y][x][c];
                }
            }
        }
        return flat;
    }

    /**
     * NSFW 综合分 = hentai + porn + sexy 三类之和（softmax 概率和，范围 0–1）
     * <p>TODO 核对：落位模型若直接输出 NSFW 单值或类目顺序不同，调整本方法</p>
     *
     * 从模型输出中取 hentai+porn+sexy 三类概率之和作为 NSFW 综合分（0–1）
     */
    private double nsfwScore(float[] classScores) {
        // softmax 概率和（0–1）：三类之和近似 NSFW 综合分；TODO 核对落位模型输出形态
        return (classScores[IDX_HENTAI] + classScores[IDX_PORN] + classScores[IDX_SEXY]);
    }

    /**
     * @PreDestroy 生命周期钩子：关闭线程池 + 释放 ONNX session 原生资源
     */
    @PreDestroy
    public void destroy() {
        // 先停线程池（不再接受新推理）再释放原生内存；close 抛错仅告警——
        // 退出路径上原生资源随进程回收，不阻断停机
        inferExecutor.shutdown();
        try {
            session.close();
        } catch (OrtException e) {
            log.warn("ONNX session 关闭异常（应用正在退出，不影响数据一致性）：{}", e.getMessage());
        }
    }

    /**
     * 工具方法：计算耗时毫秒数（当前时间 - 起始时间）
     * @param start
     * @return
     */
    private long costMs(long start) {
        return System.currentTimeMillis() - start;
    }
}