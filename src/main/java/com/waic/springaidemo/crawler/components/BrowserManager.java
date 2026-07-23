package com.waic.springaidemo.crawler.components;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ColorScheme;
import com.waic.springaidemo.crawler.config.CrawlerProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Function;

/**
 * 浏览器管理器，仅负责 Playwright/Browser 的创建、销毁与页面生命周期维护。
 * 具体的抓取逻辑（导航、等待、取内容）由调用方通过 {@link #withPage(Function)} 提供。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserManager {

    private final CrawlerProperties crawlerProperties;

    private Playwright playwright;
    private Browser headlessBrowser;
    /** volatile：双重检查锁定下，保证初始化线程对 headfulBrowser/headfulContext 的写入对其他线程可见，避免读到半初始化对象 */
    private volatile Browser headfulBrowser;
    /** 有头浏览器复用的持久化上下文，承载人工登录态（cookie/localStorage） */
    private volatile BrowserContext headfulContext;

    /** 从浏览器内核层面移除自动化痕迹，与 init 脚本互补 */
    private static final String DISABLE_AUTOMATION_CONTROLLED_ARG = "--disable-blink-features=AutomationControlled";

    /**
     * 隐身注入脚本：在页面脚本执行前覆盖暴露自动化的 JS 特征。
     * 与启动参数 {@value #DISABLE_AUTOMATION_CONTROLLED_ARG} 互补，从内核与 JS 两层抹除痕迹。
     */
    private static final String STEALTH_INIT_SCRIPT = """
            () => {
                const noop = () => {};
                const define = (obj, prop, value) => {
                    try { Object.defineProperty(obj, prop, { get: () => value, configurable: true }); } catch (e) {}
                };
                // 1) 自动化标志
                define(navigator, 'webdriver', false);
                // 2) 伪造 chrome 运行时对象（无头默认缺失）
                if (!window.chrome) {
                    try { window.chrome = { runtime: {}, loadTimes: noop, csi: noop, app: {} }; } catch (e) {}
                }
                // 3) 语言
                define(navigator, 'languages', ['zh-CN', 'zh']);
                // 4) 插件（无头默认空，补若干以更像真人）
                define(navigator, 'plugins', [1, 2, 3, 4, 5]);
                // 5) 平台与 UA 自洽（默认 UA 为 Windows，故平台填 Win32）
                define(navigator, 'platform', 'Win32');
                // 6) 伪造 userAgentData，去掉 HeadlessChrome/Chromium 暴露
                define(navigator, 'userAgentData', {
                    mobile: false,
                    platform: 'Windows',
                    brands: [
                        { brand: 'Chromium', version: '124' },
                        { brand: 'Google Chrome', version: '124' },
                        { brand: 'Not)A;Brand', version: '99' }
                    ],
                    getHighEntropyValues: (list) => Promise.resolve({})
                });
            }
            """;

    /**
     * 应用启动时初始化 Playwright 无头浏览器。
     * 初始化失败会抛出异常，阻断 Spring 容器启动，便于快速发现部署/依赖问题。
     */
    @PostConstruct
    public void initialize() {
        if (playwright != null) {
            return;
        }
        log.info("Initializing Playwright chromium browser at startup...");
        try {
            playwright = Playwright.create();
            headlessBrowser = playwright.chromium()
                    .launch(new BrowserType.LaunchOptions()
                            .setArgs(List.of(DISABLE_AUTOMATION_CONTROLLED_ARG)));
            log.info("Playwright chromium browser initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize Playwright browser at startup", e);
            throw new IllegalStateException("Failed to initialize Playwright browser at startup", e);
        }
    }

    /**
     * 打开一个新页面供 action 使用，action 执行完毕后自动关闭页面。
     * 页面的创建与销毁完全收口在本方法内，调用方只需关注"拿到 page 后做什么"。
     *
     * @param action 在页面上执行的操作，返回抓取结果
     * @param <T>    结果类型
     * @return action 的执行结果
     */
    public <T> T withPage(Function<Page, T> action) {
        if (headlessBrowser == null) {
            throw new IllegalStateException("Playwright browser is not initialized");
        }
        // 每次新建 context 并加载持久化登录态（若有）：使无头首抓也能复用人工登录态，
        // 命中"已登录"页而非登录墙，从而避免无谓回退有头。按调用新建 context 也天然规避并发竞态。
        boolean stealth = crawlerProperties.getAntiBot().getStealth().isEnabled();
        try (BrowserContext ctx = headlessBrowser.newContext(buildContextOptionsWithState(stealth));
             Page page = ctx.newPage()) {
            if (stealth) {
                applyStealthScript(ctx);
            }
            return action.apply(page);
        }
    }

    /**
     * 打开一个"有头"浏览器页面供 action 使用，用于人工过反爬等需要人值守的场景。
     * <p>有头浏览器与上下文均懒加载且仅初始化一次（double-checked locking）：
     * <ul>
     *   <li>复用同一个 {@link BrowserContext}，使人工登录态在同一次运行内跨多次抓取保持有效；</li>
     *   <li>若配置了 {@code storageStatePath} 且该文件存在，启动时加载其中登录态，
     *       并在每次调用结束后回写，使登录态在应用重启后依然可复用。</li>
     * </ul>
     * 注意：无显示器的服务器环境启动有头浏览器会失败，须配合配置开关使用。
     *
     * @param action 在页面上执行的操作，返回结果
     * @param <T>    结果类型
     * @return action 的执行结果
     */
    public <T> T withHeadfulPage(Function<Page, T> action) {
        if (playwright == null) {
            throw new IllegalStateException("Playwright browser is not initialized");
        }
        ensureHeadful();
        T result;
        // 复用持久化上下文：每次仅开关 page，上下文（含登录态）常驻。
        try (Page page = headfulContext.newPage()) {
            result = action.apply(page);
        }
        persistHeadfulStorageState();
        return result;
    }

    private void ensureHeadful() {
        if (headfulBrowser == null) {
            synchronized (this) {
                if (headfulBrowser == null) {
                    log.info("Initializing headful chromium browser for manual anti-bot...");
                    headfulBrowser = playwright.chromium()
                            .launch(new BrowserType.LaunchOptions()
                                    .setHeadless(false)
                                    .setArgs(List.of(DISABLE_AUTOMATION_CONTROLLED_ARG)));
                    var stealth = crawlerProperties.getAntiBot().getStealth();
                    boolean applyStealth = stealth != null && stealth.isHeadfulEnabled();
                    Browser.NewContextOptions ctxOpts = buildContextOptionsWithState(applyStealth);
                    headfulContext = headfulBrowser.newContext(ctxOpts);
                    if (applyStealth) {
                        applyStealthScript(headfulContext);
                    }
                }
            }
        }
    }

    private void persistHeadfulStorageState() {
        Path stateFile = resolveStorageStatePath();
        if (stateFile == null || headfulContext == null) {
            return;
        }
        // 原子写：先落临时文件，再 ATOMIC_MOVE 替换，避免无头高频读时读到半截 JSON。
        try {
            Path tmp = Files.createTempFile("crawler-auth-", ".tmp");
            headfulContext.storageState(new BrowserContext.StorageStateOptions().setPath(tmp));
            Files.move(tmp, stateFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.info("Persisted login state to {}", stateFile);
        } catch (Exception e) {
            log.warn("Failed to persist login state to {}", stateFile, e);
        }
    }

    /**
     * 构造带持久化登录态的 context 选项；若未配置 {@code storageStatePath} 或文件不存在，返回空白选项。
     * 无头（{@link #withPage}）与有头（{@link #ensureHeadful}）共用，保证两者读取同一份登录态。
     * <p>当 {@code applyStealth} 为 true 时，额外写入隐身参数（UA/视口/语言/时区/色彩方案），
     * 使无头抓取在请求特征层面更像真人浏览器。
     *
     * @param applyStealth 是否套用隐身参数；无头由 {@code stealth.enabled} 控制，有头由 {@code stealth.headfulEnabled} 控制
     */
    private Browser.NewContextOptions buildContextOptionsWithState(boolean applyStealth) {
        Browser.NewContextOptions opts = new Browser.NewContextOptions();
        var stealth = crawlerProperties.getAntiBot().getStealth();
        if (applyStealth && stealth != null) {
            opts.setUserAgent(stealth.getUserAgent());
            opts.setViewportSize(stealth.getViewportWidth(), stealth.getViewportHeight());
            opts.setLocale(stealth.getLocale());
            opts.setTimezoneId(stealth.getTimezoneId());
            opts.setColorScheme(parseColorScheme(stealth.getColorScheme()));
        }
        Path stateFile = resolveStorageStatePath();
        if (stateFile != null && Files.exists(stateFile)) {
            // 注意：NewContextOptions.setStorageState(String) 接收的是 storageState 的 JSON 内容，
            // 从文件加载必须用 setStorageStatePath(String)（文件路径），否则会把路径字符串当 JSON 解析报错。
            opts.setStorageStatePath(stateFile);
            log.info("Loaded persisted login state from {}", stateFile);
        }
        return opts;
    }

    /**
     * 向已建好的 context 注入隐身脚本，覆盖暴露自动化的 JS 特征。
     * 必须在 {@code newPage()} 之前调用，确保脚本先于页面脚本执行。
     *
     * @param ctx 目标 context
     */
    private void applyStealthScript(BrowserContext ctx) {
        if (ctx == null) {
            return;
        }
        ctx.addInitScript(STEALTH_INIT_SCRIPT);
        log.info("Applied stealth init script to browser context");
    }

    /**
     * 将配置中的色彩方案字符串解析为 Playwright 枚举，解析失败回退 {@code LIGHT}。
     */
    private static ColorScheme parseColorScheme(String scheme) {
        if (scheme == null || scheme.isBlank()) {
            return ColorScheme.LIGHT;
        }
        try {
            return ColorScheme.valueOf(scheme.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ColorScheme.LIGHT;
        }
    }

    private Path resolveStorageStatePath() {
        String path = crawlerProperties.getAntiBot().getStorageStatePath();
        if (path == null || path.isBlank()) {
            return null;
        }
        return Paths.get(path);
    }

    @PreDestroy
    public void destroy() {
        log.info("Closing Playwright resources");
        if (headlessBrowser != null) {
            headlessBrowser.close();
        }
        if (headfulContext != null) {
            headfulContext.close();
        }
        if (headfulBrowser != null) {
            headfulBrowser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }
}
