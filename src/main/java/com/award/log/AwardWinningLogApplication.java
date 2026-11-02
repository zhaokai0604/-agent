package com.award.log;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.awt.Desktop;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * ThreshCore 后端启动类：启用异步、定时任务，并扫描 MyBatis Mapper。
 * 启动成功后打印控制台与 Knife4j 文档地址，不在此注册业务 Bean。
 */
@Slf4j
@EnableAsync
@EnableScheduling
@SpringBootApplication
@MapperScan("com.award.log.mapper")
public class AwardWinningLogApplication {
    public static void main(String[] args) throws UnknownHostException {
        ConfigurableApplicationContext application = SpringApplication.run(AwardWinningLogApplication.class, args);
        Environment env = application.getEnvironment();
        String ip = InetAddress.getLocalHost().getHostAddress();
        String port = env.getProperty("server.port");
        String path = env.getProperty("server.servlet.context-path");
        if (path == null) {
            path = "";
        }
        log.info("\n----------------------------------------------------------\n\t" +
                "  _____ _    _  _____ _____ ______  _____ _____ \n\t" +
                " / ____| |  | |/ ____/ ____|  ____|/ ____/ ____|\n\t" +
                "| (___ | |  | | |   | |    | |__  | (___| (___  \n\t" +
                " \\___ \\| |  | | |   | |    |  __|  \\___ \\___ \\ \n\t" +
                " ____) | |__| | |___| |____| |____ ____) |___) |\n\t" +
                "|_____/ \\___/ \\_____\\_____|______|_____/_____/ \n\t" +
                "----------------------------------------------------------\n\t" +
                "应用 [ThreshCore] 启动成功! (STARTED SUCCESS)\n\t" +
                "控制台: \t\thttp://localhost:" + port + path + "/\n\t" +
                "相关地址: \thttp://" + ip + ":" + port + path + "/\n\t" +
                "接口文档: \thttp://localhost:" + port + path + "/doc.html\n" +
                "----------------------------------------------------------");
        if (Boolean.parseBoolean(env.getProperty("threshcore.desktop.open-browser", "false"))) {
            openDesktopBrowser("http://127.0.0.1:" + port + path + "/");
        }
    }

    private static void openDesktopBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                log.info("已打开系统浏览器: {}", url);
                return;
            }
            String comSpec = System.getenv().getOrDefault("ComSpec", "cmd.exe");
            new ProcessBuilder(comSpec, "/c", "start", "", url).start();
            log.info("已通过 Windows Shell 打开系统浏览器: {}", url);
        } catch (Exception e) {
            log.warn("自动打开系统浏览器失败，请手动打开 {}: {}", url, e.getMessage());
        }
    }
}
