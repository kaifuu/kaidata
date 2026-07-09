package com.pharma.service.security;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 图形验证码生成器（数据安全域）
 * <p>
 * 纯 Java AWT 离屏渲染，零第三方依赖。BufferedImage 绘制不依赖显示设备，
 * 在 Spring Boot 默认 headless=true 下不会抛 HeadlessException。
 * <p>
 * 输出 4 位字符图（剔除易混 0/O/1/I/l），深色霓虹配色贴合登录页科技感。
 */
public final class CaptchaUtil {

    /** 字符集：剔除易混的 0/O、1/I/l */
    private static final char[] CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /** 霓虹配色（与 tech-theme.css 暗色主题一致） */
    private static final Color[] NEON = {
            new Color(0x00, 0xE0, 0xFF),  // 青
            new Color(0x7C, 0x5C, 0xFF),  // 紫
            new Color(0x2F, 0x6B, 0xFF)   // 蓝
    };

    private static final int WIDTH = 130;
    private static final int HEIGHT = 44;
    private static final int LEN = 4;

    private CaptchaUtil() {}

    /** 生成验证码：返回明文 code（服务端暂存校验）与 base64 图片（前端展示） */
    public static CaptchaImg generate() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        StringBuilder code = new StringBuilder(LEN);   // 提到 try 外：return 在块外引用
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // 半透明深色圆角底：贴在玻璃卡上仍有对比
            g.setColor(new Color(6, 12, 28, 165));
            g.fillRoundRect(0, 0, WIDTH, HEIGHT, 12, 12);

            // 4 位字符，每字随机倾斜 ±25°
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, 28);
            for (int i = 0; i < LEN; i++) {
                char c = CHARS[r.nextInt(CHARS.length)];
                code.append(c);
                AffineTransform at = new AffineTransform();
                at.translate(16 + i * 28, 0);
                at.rotate(Math.toRadians(r.nextDouble(-25, 25)));
                g.setTransform(at);
                g.setFont(font);
                g.setColor(NEON[r.nextInt(NEON.length)]);
                g.drawString(String.valueOf(c), 0, 31);
            }
            g.setTransform(new AffineTransform());  // 复位

            // 干扰线 3~5 条
            for (int i = 0, n = r.nextInt(3, 6); i < n; i++) {
                g.setColor(withAlpha(NEON[r.nextInt(NEON.length)], 95));
                g.setStroke(new BasicStroke(1.4f));
                g.drawLine(r.nextInt(WIDTH), r.nextInt(HEIGHT), r.nextInt(WIDTH), r.nextInt(HEIGHT));
            }

            // 噪点 25~35 个
            for (int i = 0, n = r.nextInt(25, 36); i < n; i++) {
                g.setColor(withAlpha(NEON[r.nextInt(NEON.length)], 150));
                g.fillRect(r.nextInt(WIDTH), r.nextInt(HEIGHT), 2, 2);
            }
        } finally {
            g.dispose();
        }
        return new CaptchaImg(code.toString(), "data:image/png;base64," + toBase64Png(img));
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private static String toBase64Png(BufferedImage img) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("生成验证码图片失败", e);
        }
    }

    /** 验证码生成结果：明文 code + base64 图片 */
    public record CaptchaImg(String code, String img) {}
}
