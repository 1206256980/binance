package org.example;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import spark.Spark;

import javax.net.ssl.HttpsURLConnection;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class BinanceCombinedServer {

    // ------------------- 公共配置 -------------------
    private static final String EXCHANGE_INFO_URL = "https://fapi.binance.com/fapi/v1/exchangeInfo";
    private static final String KLINES_URL = "https://fapi.binance.com/fapi/v1/klines";
    private static final String TICKER_PRICE_URL = "https://fapi.binance.com/fapi/v1/ticker/price";
    private static final String POSITION_RISK_URL = "https://fapi.binance.com/fapi/v2/positionRisk";
    private static final String WX_PUSHER_URL = "https://wxpusher.zjiecode.com/api/send/message/simple-push";
    private static final String WX_PUSHER_SPT = "SPT_czS4n18uCRSQTUJtPSr1ZiRa3737";

    // 🌟 币安 API Key（用于获取 MMR 数据）
    private static final String BINANCE_API_KEY = "piFGDiG2hwjXzKiC0OfoP6CMhHSGcyWVDBhJlFNR7EZuS0ooZodwOScTQrx9uOXk";
    private static final String BINANCE_SECRET_KEY = "UpUsxSklT2PCfxYgoDmMrQMMUoTTY4k73pEYNs9Gxg9vGpSdaFjrnhw13eHjUl4B";
    private static final int THREADS = 50;
    private static final int DEFAULT_REFRESH_SECONDS = 35;
    private static final String[] INTERVALS = { "5m", "10m", "15m", "30m", "40m", "50m", "60m", "120m", "240m" };
    private static final int TOP_CHANGE = 20;
    private static final int TOP_AMPLITUDE = 20;
    private static final int KLINE_COUNT = 100; // 取最近 12 根 5m K 线
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(THREADS);

    // 🌟 新增配置：指数文件路径
    private static final String INDEX_FILE_PATH = "alt_futures_index_history.json";
    // 🌟 新增配置：指数计算参数
    private static final int INDEX_POOL_SIZE = 50; // Top 50 活跃币种
    private static final int INDEX_KLINE_COUNT = 6; // 30分钟 = 6 * 5m K线

    // ------------------- 缓存 -------------------
    private static volatile Map<String, List<CandleRaw>> klineCache = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, List<Candle>>> rankCache = new LinkedHashMap<>();
    private static volatile List<String> strongCache = new ArrayList<>();
    // 🌟 新增缓存：用于存储指数历史数据
    private static volatile List<IndexPoint> indexHistoryCache = new ArrayList<>();
    // ------------------- 指数计算控制 -------------------
    private static final long INDEX_CALCULATION_INTERVAL_MS = 3 * 60 * 1000; // 3 分钟的毫秒数 (180,000 ms)
    private static volatile long lastIndexCalculationTime = 0; // 记录上次指数计算的时间点

    // ------------------- 按需刷新控制 -------------------
    private static volatile long lastRefreshTime = 0; // 记录上次数据刷新时间
    private static final long REFRESH_INTERVAL_MS = DEFAULT_REFRESH_SECONDS * 1000; // 刷新间隔(毫秒)
    private static volatile boolean isRefreshing = false; // 防止并发刷新

    // 🌟 新增配置：DCA 配置文件路径
    private static final String DCA_FILE_PATH = "dca_settings_history.json";
    private static volatile String dcaSettingsCache = "{\"groups\":[],\"groupIdCounter\":0,\"globalRowIdCounter\":0,\"globalWalletBalance\":\"\"}";

    // 🌟 新增配置：价格提醒配置文件路径
    private static final String PRICE_ALERT_FILE_PATH = "price_alerts.json";
    private static List<PriceAlert> priceAlerts = new CopyOnWriteArrayList<>();
    private static Map<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();
    private static Map<String, BigDecimal> lastPnls = new ConcurrentHashMap<>();

    // ------------------- 数据模型 -------------------
    static class CandleRaw {
        BigDecimal open, high, low, close, volume;
        long openTime; // 🌟 增加时间点，用于指数时间戳

        CandleRaw(long ot, BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c, BigDecimal v) {
            this.openTime = ot;
            open = o;
            high = h;
            low = l;
            close = c;
            volume = v;
        }
    }

    static class Candle {
        String symbol;
        BigDecimal open, high, low, close, change, amplitude;
        Map<String, Map<String, BigDecimal>> others;

        Candle(String symbol, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
            this.symbol = symbol;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.change = close.subtract(open).divide(open, 8, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
            this.amplitude = high.subtract(low).divide(open, 8, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        }
    }

    static class StrongCoin {
        String symbol;

        StrongCoin(String s) {
            symbol = s;
        }
    }

    // 🌟 新增数据模型：指数历史点
    static class IndexPoint {
        long timestamp; // 时间戳 (毫秒)
        BigDecimal value; // AltFuturesIndex 值

        public IndexPoint(long timestamp, BigDecimal value) {
            this.timestamp = timestamp;
            this.value = value.setScale(4, RoundingMode.HALF_UP); // 保留 4 位小数
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    // 🌟 新增数据模型：用于指数计算时的排序和暂存
    private static class IndexData {
        String symbol;
        BigDecimal change; // Delta P_i (30分钟价格变动百分比)
        BigDecimal tradeValue; // V_i (30分钟总成交额 / 交易价值)

        public IndexData(String symbol, BigDecimal change, BigDecimal tradeValue) {
            this.symbol = symbol;
            this.change = change;
            this.tradeValue = tradeValue;
        }
    }

    // 🌟 新增数据模型：价格提醒
    static class PriceAlert {
        String id;
        String symbol;
        BigDecimal targetPrice;
        String type; // "price_reached"
        String frequency; // "once", "continuous"
        boolean isTriggered; // 对于 "once" 类型，触发后标记为已触发
        boolean enabled = true; // 🌟 启动开关
        long lastTriggerTime = 0; // 🌟 上次触发时间
        int cooldownSeconds = 60; // 🌟 冷却时间（秒）

        public PriceAlert() {
            this.id = UUID.randomUUID().toString();
        }

        public PriceAlert(String symbol, BigDecimal targetPrice, String type, String frequency) {
            this.id = UUID.randomUUID().toString();
            this.symbol = symbol.toUpperCase();
            this.targetPrice = targetPrice;
            this.type = type;
            this.frequency = frequency;
            this.isTriggered = false;
        }
    }

    private static volatile List<String> cachedSymbols = new ArrayList<>();
    private static volatile long cachedSymbolsTime = 0;
    private static final long SYMBOLS_CACHE_DURATION = 60 * 60 * 1000; // 10分钟

    // 强势币使用的 K 线根数（6 根 5m -> 30 分钟）
    private static final int STRONG_KLINE_COUNT = 6;

    public static void main(String[] args) throws Exception {
        initProxy();
        loadDcaSettingsFromFile();
        Spark.port(4567);
        Spark.staticFiles.location("/public");

        loadPriceAlertsFromFile();

        // 🌟 价格提醒：每 3 秒检查一次（需要实时监控）
        ScheduledExecutorService alertScheduler = Executors.newScheduledThreadPool(1);
        alertScheduler.scheduleAtFixedRate(() -> {
            try {
                checkPriceAlerts();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 3, 3, TimeUnit.SECONDS);

        // 🌟 排行榜数据：改为按需刷新（用户访问时才调用币安API，节省资源）
        // 移除了原来的定时刷新任务，改为在 /data 和 /strong 接口中按需刷新

        Spark.get("/data", (req, res) -> {
            res.type("application/json; charset=UTF-8");
            // 🌟 按需刷新：检查缓存是否过期
            refreshIfNeeded();
            return new GsonBuilder().setPrettyPrinting().create().toJson(rankCache);
        });

        Spark.get("/strong", (req, res) -> {
            res.type("application/json; charset=UTF-8");
            // 🌟 按需刷新：检查缓存是否过期
            refreshIfNeeded();
            return new GsonBuilder().setPrettyPrinting().create()
                    .toJson(strongCache.stream().map(StrongCoin::new).collect(Collectors.toList()));
        });

        // 🌟 新增接口：获取 DCA 配置
        Spark.get("/dca-settings", (req, res) -> {
            res.type("application/json; charset=UTF-8");
            return dcaSettingsCache;
        });

        // 🌟 新增接口：同步 DCA 配置 (全量覆盖)
        Spark.post("/dca-settings", (req, res) -> {
            String body = req.body();
            if (body != null && !body.isEmpty()) {
                dcaSettingsCache = body;
                saveDcaSettingsToFile(body);
                return "{\"status\":\"ok\"}";
            }
            res.status(400);
            return "{\"status\":\"error\",\"message\":\"Empty body\"}";
        });

        // 🌟 新增接口：代理获取 MMR 数据（因为币安 API 需要 CORS 或认证）
        Spark.get("/mmr-data", (req, res) -> {
            res.type("application/json; charset=UTF-8");
            try {
                String symbol = req.queryParams("symbol");
                String baseUrl = "https://fapi.binance.com/fapi/v1/leverageBracket";
                String queryParams = "";
                if (symbol != null && !symbol.isEmpty()) {
                    queryParams = "symbol=" + URLEncoder.encode(symbol, "UTF-8");
                }
                // 🌟 使用带签名的请求方法
                String result = httpGetWithSignature(baseUrl, queryParams);
                return result;
            } catch (Exception e) {
                e.printStackTrace();
                res.status(500);
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        });

        // 🌟 新增接口：获取所有提醒
        Spark.get("/price-alerts", (req, res) -> {
            res.type("application/json; charset=UTF-8");
            return new Gson().toJson(priceAlerts);
        });

        // 🌟 新增接口：保存所有提醒 (全量覆盖)
        Spark.post("/price-alerts", (req, res) -> {
            String body = req.body();
            if (body != null && !body.isEmpty()) {
                try {
                    PriceAlert[] alerts = new Gson().fromJson(body, PriceAlert[].class);
                    priceAlerts.clear();
                    if (alerts != null) {
                        for (PriceAlert alert : alerts) {
                            if (alert.id == null || alert.id.isEmpty()) {
                                alert.id = UUID.randomUUID().toString();
                            }
                            priceAlerts.add(alert);
                        }
                    }
                    savePriceAlertsToFile();
                    return "{\"status\":\"ok\"}";
                } catch (Exception e) {
                    e.printStackTrace();
                    res.status(500);
                    return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
                }
            }
            res.status(400);
            return "{\"status\":\"error\",\"message\":\"Empty body\"}";
        });

        // 🌟 新增接口：获取标记价格
        Spark.get("/mark-price", (req, res) -> {
            res.type("application/json; charset=UTF-8");
            try {
                String symbol = req.queryParams("symbol");
                if (symbol == null || symbol.isEmpty()) {
                    res.status(400);
                    return "{\"error\":\"Missing symbol\"}";
                }
                String url = "https://fapi.binance.com/fapi/v1/premiumIndex?symbol="
                        + URLEncoder.encode(symbol, "UTF-8");
                String result = httpGet(url);
                return result;
            } catch (Exception e) {
                e.printStackTrace();
                res.status(500);
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        });

    }

    // ------------------- 刷新逻辑 -------------------

    // 🌟 按需刷新：只有当缓存过期时才刷新数据
    private static synchronized void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        // 如果距离上次刷新不足间隔时间，或者正在刷新中，直接返回
        if ((now - lastRefreshTime) < REFRESH_INTERVAL_MS || isRefreshing) {
            return;
        }
        isRefreshing = true;
        try {
            refreshAllData();
            lastRefreshTime = System.currentTimeMillis();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            isRefreshing = false;
        }
    }

    private static void refreshAllData() throws Exception {
        long start = System.currentTimeMillis();
        List<String> symbols = getAllSymbolsCached();
        Map<String, List<CandleRaw>> newKlineCache = new ConcurrentHashMap<>();

        // 一次拉取所有交易对 K 线
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String symbol : symbols) {
            futures.add(CompletableFuture.runAsync(() -> {
                // 🌟 fetch5mKlines 现在返回带时间戳的 CandleRaw
                List<CandleRaw> klines = fetch5mKlines(symbol, KLINE_COUNT);
                if (klines != null && !klines.isEmpty())
                    newKlineCache.put(symbol, klines);
            }, EXECUTOR));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long used = System.currentTimeMillis() - start;
        System.out.println("全部请求完成，耗时：" + used + "ms");

        klineCache = newKlineCache;

        // ---------------- 排行榜逻辑 ---------------- (代码保持不变，省略以保持简洁，但请在您的文件中保留)
        // ... (原有的排行榜逻辑)
        Map<String, Map<String, Candle>> allMap = new ConcurrentHashMap<>();
        for (String symbol : klineCache.keySet()) {
            List<CandleRaw> klines = klineCache.get(symbol);
            if (klines == null || klines.isEmpty())
                continue;
            Map<String, Candle> map = new HashMap<>();

            // 遍历所有 INTERVALS，计算需要多少根 5m K 线
            for (String interval : INTERVALS) {
                int minutes = Integer.parseInt(interval.replace("m", ""));
                int needed = minutes / 5;
                if (klines.size() >= needed) {
                    List<CandleRaw> sub = klines.subList(klines.size() - needed, klines.size());
                    map.put(interval, aggregate(symbol, sub));
                }
            }
            allMap.put(symbol, map);
        }

        // 构建排行榜
        for (String interval : INTERVALS) {
            List<Candle> candles = new ArrayList<>();
            for (String symbol : symbols) {
                Map<String, Candle> m = allMap.get(symbol);
                if (m != null && m.containsKey(interval)) {
                    Candle c = m.get(interval);
                    Map<String, Map<String, BigDecimal>> others = new HashMap<>();
                    for (String i2 : INTERVALS) {
                        Candle c2 = m.get(i2);
                        if (c2 != null) {
                            Map<String, BigDecimal> map2 = new HashMap<>();
                            map2.put("change", c2.change);
                            map2.put("amplitude", c2.amplitude);
                            others.put(i2, map2);
                        }
                    }
                    c.others = others;
                    candles.add(c);
                }
            }
            Map<String, List<Candle>> intervalMap = new HashMap<>();
            intervalMap.put("change", candles.stream()
                    .sorted((a, b) -> b.change.compareTo(a.change))
                    .limit(TOP_CHANGE)
                    .collect(Collectors.toList()));
            // 振幅排行榜数据去掉
            // intervalMap.put("amplitude", candles.stream()
            // .sorted((a, b) -> b.amplitude.compareTo(a.amplitude))
            // .limit(TOP_AMPLITUDE)
            // .collect(Collectors.toList()));
            rankCache.put(interval, intervalMap);
        }

        // ---------------- 强势币逻辑 ---------------- (代码保持不变，省略以保持简洁，但请在您的文件中保留)
        List<String> strongs = new ArrayList<>();
        // ... (原有的强势币逻辑，这里不再重复粘贴)

        for (String symbol : symbols) {
            List<CandleRaw> rawsAll = klineCache.get(symbol);
            if (rawsAll == null || rawsAll.size() < STRONG_KLINE_COUNT)
                continue;

            // ... (原有的强势币计算逻辑)

            // ------------------- 核心变量定义 -------------------
            List<CandleRaw> lastN = rawsAll.subList(rawsAll.size() - STRONG_KLINE_COUNT, rawsAll.size());
            BigDecimal highMax = lastN.stream().map(c -> c.high).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal current = lastN.get(lastN.size() - 1).close;
            BigDecimal firstOpen = lastN.get(0).open;
            BigDecimal currentOpen = lastN.get(lastN.size() - 1).open; // 当前 5m K 线的开盘价

            // ... (计算 PosRatio, CumChange, MaxVol, isComboOne, isVolumeSpikeAndSurge 等)

            // ----------------------------------------------------
            // (A) 组合一：价格位置、累计涨幅、最大成交额
            // ----------------------------------------------------
            boolean isComboOne = false;

            // 1. PosRatio 计算
            BigDecimal posRatio = BigDecimal.ZERO;
            BigDecimal denominator = highMax.subtract(firstOpen);
            if (denominator.compareTo(BigDecimal.ZERO) > 0) {
                posRatio = current.subtract(firstOpen).divide(denominator, 8, RoundingMode.HALF_UP);
            }

            // 2. CumChange 计算
            BigDecimal cumChange = BigDecimal.ZERO;
            if (firstOpen.compareTo(BigDecimal.ZERO) > 0) {
                cumChange = current.subtract(firstOpen).multiply(new BigDecimal("100"))
                        .divide(firstOpen, 8, RoundingMode.HALF_UP);
            }

            // 3. MaxVol 计算 (Volume * Close 的最大值)
            BigDecimal maxVol = lastN.stream()
                    .map(c -> c.volume.multiply(c.close))
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            // 组合一判断
            if (posRatio.compareTo(new BigDecimal("0.7")) >= 0 &&
                    cumChange.compareTo(new BigDecimal("9")) >= 0) {
                isComboOne = true;
                System.out.println("强势币：" + symbol + ",区间百分比:" + posRatio + "，累计涨幅：" + cumChange);
            }

            // ----------------------------------------------------
            // (B) 组合二：成交量突增 AND 5m 暴涨（新逻辑）
            // ----------------------------------------------------
            boolean isVolumeSpikeAndSurge = false;

            // 1. 计算当前 5m 涨幅
            BigDecimal current5mChange = BigDecimal.ZERO;
            if (currentOpen.compareTo(BigDecimal.ZERO) > 0) {
                current5mChange = current.subtract(currentOpen).multiply(new BigDecimal("100"))
                        .divide(currentOpen, 8, RoundingMode.HALF_UP);
            }

            // 2. 当前 K 线的原始成交量
            BigDecimal currentVolume = lastN.get(lastN.size() - 1).volume;

            // 3. 前 N-1 根 K 线的最大原始成交量
            BigDecimal previousMaxVolume = lastN.subList(0, lastN.size() - 1)
                    .stream()
                    .map(c -> c.volume)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            // 组合二判断： [成交量突增] AND [5m 涨幅 >= 5%]
            if (previousMaxVolume.compareTo(BigDecimal.ZERO) > 0) {
                // Condition 1: Volume Spike
                boolean volumeCondition = currentVolume
                        .compareTo(previousMaxVolume.multiply(new BigDecimal("4.0"))) >= 0;// 成交量4倍量爆量

                // Condition 2: Price Surge (5m Change >= 5%)
                boolean surgeCondition = current5mChange.compareTo(new BigDecimal("5")) >= 0;// 涨幅大于5

                if (volumeCondition && surgeCondition) {
                    isVolumeSpikeAndSurge = true;
                    System.out.println("强势币：" + symbol + ",当前涨幅:" + surgeCondition + "，成交量倍数：" + volumeCondition);
                }
            }

            // ----------------------------------------------------
            // (C) 最终或逻辑 (OR Logic)
            // ----------------------------------------------------
            if (isComboOne || isVolumeSpikeAndSurge) {
                strongs.add(symbol);
            }

        }
        strongCache = strongs;
    }

    // ------------------- 工具方法 -------------------
    private static List<String> getAllSymbolsCached() throws Exception {
        long now = System.currentTimeMillis();
        if (!cachedSymbols.isEmpty() && (now - cachedSymbolsTime < SYMBOLS_CACHE_DURATION)) {
            return cachedSymbols;
        }

        String json = httpGet(EXCHANGE_INFO_URL);
        if (json == null || json.isEmpty())
            return Collections.emptyList();
        Gson gson = new Gson();
        JsonObject obj = gson.fromJson(json, JsonObject.class);
        JsonArray arr = obj.getAsJsonArray("symbols");

        List<String> list = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject symObj = el.getAsJsonObject();
            String symbol = symObj.get("symbol").getAsString();
            // 只保留状态为 TRADING 的 USDT 交易对，过滤掉已下架或暂停交易的币种
            String status = symObj.has("status") ? symObj.get("status").getAsString() : "";
            if (symbol.endsWith("USDT") && "TRADING".equals(status)) {
                list.add(symbol);
            }
        }

        // 去重
        cachedSymbols = new ArrayList<>(new LinkedHashSet<>(list));
        System.out.println("一共获取到" + cachedSymbols.size() + "个交易对");
        cachedSymbolsTime = now;

        return cachedSymbols;
    }

    static int count = 0;

    private static List<CandleRaw> fetch5mKlines(String symbol, int limit) {
        try {
            long start = System.currentTimeMillis();
            String url = KLINES_URL + "?symbol=" + URLEncoder.encode(symbol, "UTF-8") + "&interval=5m&limit=" + limit;
            String json = httpGet(url);
            long end = System.currentTimeMillis() - start;
            if (count % 300 == 0) {// 每三百次请求打一次日志
                System.out.println("接口返回,symbol:" + symbol + "耗时：" + end + ",json:" + json);
                System.out.println("-------------------------------------------");
            }
            count++;
            if (json == null || json.isEmpty())
                return Collections.emptyList();
            Gson gson = new Gson();
            JsonArray arr = gson.fromJson(json, JsonArray.class);
            List<CandleRaw> list = new ArrayList<>();
            for (JsonElement el : arr) {
                JsonArray k = el.getAsJsonArray();
                long openTime = k.get(0).getAsLong(); // 🌟 获取 K 线起始时间戳
                BigDecimal open = k.get(1).getAsBigDecimal();
                BigDecimal high = k.get(2).getAsBigDecimal();
                BigDecimal low = k.get(3).getAsBigDecimal();
                BigDecimal close = k.get(4).getAsBigDecimal();
                BigDecimal volume = k.get(5).getAsBigDecimal();
                list.add(new CandleRaw(openTime, open, high, low, close, volume)); // 🌟 传入时间戳
            }
            return list;
        } catch (Exception e) {
            System.out.println(e.getMessage());
            return Collections.emptyList();
        }
    }

    private static Candle aggregate(String symbol, List<CandleRaw> raws) {
        BigDecimal open = raws.get(0).open;
        BigDecimal close = raws.get(raws.size() - 1).close;
        BigDecimal high = raws.stream().map(r -> r.high).max(BigDecimal::compareTo).get();
        BigDecimal low = raws.stream().map(r -> r.low).min(BigDecimal::compareTo).get();
        return new Candle(symbol, open, high, low, close);
    }

    // 🌟 新增：高频价格检查逻辑
    private static void checkPriceAlerts() {
        // 🌟 只有当存在启用的提醒时才调用币安API
        List<PriceAlert> enabledAlerts = priceAlerts.stream().filter(a -> a.enabled).collect(Collectors.toList());
        if (enabledAlerts.isEmpty())
            return;

        // 分类提醒：价格类 vs 盈亏类
        boolean hasPriceAlerts = enabledAlerts.stream().anyMatch(a -> "price_reached".equals(a.type));
        boolean hasPnLAlerts = enabledAlerts.stream()
                .anyMatch(a -> "profit_reached".equals(a.type) || "loss_reached".equals(a.type));

        Map<String, BigDecimal> currentTickerPrices = new HashMap<>();
        if (hasPriceAlerts) {
            String json = httpGet(TICKER_PRICE_URL);
            if (json != null && !json.isEmpty()) {
                JsonArray arr = new Gson().fromJson(json, JsonArray.class);
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    currentTickerPrices.put(obj.get("symbol").getAsString(), obj.get("price").getAsBigDecimal());
                }
            }
        }

        JsonArray positions = null;
        if (hasPnLAlerts) {
            String json = httpGetWithSignature(POSITION_RISK_URL, "");
            if (json != null && !json.contains("\"error\"")) {
                positions = new Gson().fromJson(json, JsonArray.class);
                // System.out.println("DEBUG: 已获取持仓数据，条数: " + positions.size());
            } else {
                System.err.println("❌ 获取持仓失败: " + json);
            }
        }

        // 🌟 新增：提取并计算所有当前的 PnL 情况，防止在循环中更新状态导致后续提醒失效
        Map<String, BigDecimal> currentPnLMap = new HashMap<>();
        if (positions != null) {
            BigDecimal totalAccPnL = BigDecimal.ZERO;
            for (JsonElement p : positions) {
                JsonObject obj = p.getAsJsonObject();
                BigDecimal pnl = obj.get("unRealizedProfit").getAsBigDecimal();
                String sym = obj.get("symbol").getAsString();
                currentPnLMap.put(sym, currentPnLMap.getOrDefault(sym, BigDecimal.ZERO).add(pnl));
                totalAccPnL = totalAccPnL.add(pnl);
            }
            currentPnLMap.put("ACCOUNT", totalAccPnL);
        }

        long now = System.currentTimeMillis();
        for (PriceAlert alert : enabledAlerts) {
            try {
                // 🌟 冷却时间检查
                if (now - alert.lastTriggerTime < (long) alert.cooldownSeconds * 1000) {
                    continue;
                }

                if ("price_reached".equals(alert.type)) {
                    // 原有的价格提醒逻辑
                    if (alert.symbol == null || alert.symbol.isEmpty() || alert.targetPrice == null)
                        continue;

                    BigDecimal currentPrice = currentTickerPrices.get(alert.symbol);
                    BigDecimal lastPrice = lastPrices.get(alert.symbol);

                    if (currentPrice != null) {
                        if (lastPrice != null) {
                            boolean triggered = false;
                            if (lastPrice.compareTo(alert.targetPrice) < 0
                                    && currentPrice.compareTo(alert.targetPrice) >= 0)
                                triggered = true;
                            else if (lastPrice.compareTo(alert.targetPrice) > 0
                                    && currentPrice.compareTo(alert.targetPrice) <= 0)
                                triggered = true;

                            if (triggered) {
                                System.out.println("🚨 触发价格提醒: " + alert.symbol + " 当前价: " + currentPrice + " 目标价: "
                                        + alert.targetPrice);
                                sendWxPusherNotification(alert, currentPrice, alert.targetPrice);
                                alert.lastTriggerTime = now;
                                if ("once".equals(alert.frequency)) {
                                    alert.isTriggered = true;
                                    alert.enabled = false;
                                }
                                savePriceAlertsToFile();
                            }
                        }
                    }
                } else if ("profit_reached".equals(alert.type) || "loss_reached".equals(alert.type)
                        || "profit_step".equals(alert.type) || "loss_step".equals(alert.type)) {
                    // 🌟 盈亏提醒逻辑 (支持固定阈值和步进)
                    if (positions == null || alert.targetPrice == null
                            || alert.targetPrice.compareTo(BigDecimal.ZERO) <= 0)
                        continue;

                    String pnlKey = (alert.symbol == null || alert.symbol.trim().isEmpty()) ? "ACCOUNT"
                            : alert.symbol.trim().toUpperCase();
                    BigDecimal currentPnL = currentPnLMap.getOrDefault(pnlKey, BigDecimal.ZERO);
                    BigDecimal lastPnL = lastPnls.get(pnlKey);

                    if (lastPnL != null) {
                        boolean triggered = false;
                        String triggerMsg = "";

                        if ("profit_reached".equals(alert.type) || "loss_reached".equals(alert.type)) {
                            // 固定阈值逻辑
                            BigDecimal targetThreshold = "profit_reached".equals(alert.type) ? alert.targetPrice
                                    : alert.targetPrice.negate();

                            if (lastPnL.compareTo(targetThreshold) < 0 && currentPnL.compareTo(targetThreshold) >= 0)
                                triggered = true;
                            else if (lastPnL.compareTo(targetThreshold) > 0
                                    && currentPnL.compareTo(targetThreshold) <= 0)
                                triggered = true;

                            if (triggered) {
                                triggerMsg = ("profit_reached".equals(alert.type) ? "盈利" : "亏损") + "达到阈值: "
                                        + targetThreshold;
                                // 🌟 传递固定阈值作为显示目标
                                sendWxPusherNotification(alert, currentPnL, alert.targetPrice);
                                alert.lastTriggerTime = now;
                                if ("once".equals(alert.frequency)) {
                                    alert.isTriggered = true;
                                    alert.enabled = false;
                                }
                                savePriceAlertsToFile();
                            }
                        } else {
                            // 🌟 步进逻辑 (每逢 X)
                            BigDecimal step = alert.targetPrice;
                            // 计算跨越了多少个台阶。考虑到负数，我们对亏损台阶取绝对值计算。
                            BigDecimal crossedBoundary = null;

                            if ("profit_step".equals(alert.type)) {
                                // 🌟 关键修复：profit_step 只在盈利区域内工作
                                if (currentPnL.compareTo(BigDecimal.ZERO) > 0 && lastPnL.compareTo(BigDecimal.ZERO) > 0) {
                                    long currentLevel = currentPnL.divide(step, 0, RoundingMode.FLOOR).longValue();
                                    long lastLevel = lastPnL.divide(step, 0, RoundingMode.FLOOR).longValue();

                                    if (currentLevel != lastLevel) {
                                        triggered = true;
                                        double boundaryVal = Math.max(currentLevel, lastLevel) * step.doubleValue();
                                        triggerMsg = "盈利跨越台阶: " + boundaryVal;
                                        crossedBoundary = new BigDecimal(boundaryVal);
                                    }
                                }
                            } else if ("loss_step".equals(alert.type)) {
                                //  关键修复：loss_step 只在亏损区域内工作
                                if (currentPnL.compareTo(BigDecimal.ZERO) < 0 && lastPnL.compareTo(BigDecimal.ZERO) < 0) {
                                    BigDecimal currAbsLoss = currentPnL.negate();
                                    BigDecimal lastAbsLoss = lastPnL.negate();

                                    long currentLevel = currAbsLoss.divide(step, 0, RoundingMode.FLOOR).longValue();
                                    long lastLevel = lastAbsLoss.divide(step, 0, RoundingMode.FLOOR).longValue();

                                    if (currentLevel != lastLevel) {
                                        triggered = true;
                                        double boundaryVal = Math.max(currentLevel, lastLevel) * step.doubleValue();
                                        triggerMsg = "亏损跨越台阶: " + boundaryVal;
                                        crossedBoundary = new BigDecimal(boundaryVal);
                                    }
                                }
                            }

                            if (triggered && crossedBoundary != null) {
                                String scope = (alert.symbol == null || alert.symbol.isEmpty()) ? "全账户" : alert.symbol;
                                System.out.println("🚨 触发盈亏提醒 (" + alert.type + "): " + scope + " " + triggerMsg
                                        + " 当前PnL: " + currentPnL);
                                sendWxPusherNotification(alert, currentPnL, crossedBoundary);
                                alert.lastTriggerTime = now;
                                if ("once".equals(alert.frequency)) {
                                    alert.isTriggered = true;
                                    alert.enabled = false;
                                }
                                savePriceAlertsToFile();
                            }
                        }
                    } else {
                        // 初始状态处理
                        boolean triggered = false;
                        BigDecimal initialBoundary = alert.targetPrice;

                        if ("profit_reached".equals(alert.type)) {
                            if (currentPnL.compareTo(alert.targetPrice) >= 0)
                                triggered = true;
                        } else if ("loss_reached".equals(alert.type)) {
                            if (currentPnL.compareTo(alert.targetPrice.negate()) <= 0)
                                triggered = true;
                        }
                        // 步进模式初始状态下暂不主动触发，等待下一次穿透

                        if (triggered) {
                            String scope = (alert.symbol == null || alert.symbol.isEmpty()) ? "全账户" : alert.symbol;
                            System.out.println("🚨 触发初始盈亏提醒: " + scope + " 当前盈亏: " + currentPnL);
                            sendWxPusherNotification(alert, currentPnL, initialBoundary);
                            alert.lastTriggerTime = now;
                            if ("once".equals(alert.frequency)) {
                                alert.isTriggered = true;
                                alert.enabled = false;
                            }
                            savePriceAlertsToFile();
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ 处理提醒时出错: " + alert.symbol);
                e.printStackTrace();
            }
        }

        // 🌟 循环结束后更新所有状态，确保每个提醒在当前循环中都能看到相同的“上一次状态”
        lastPrices.putAll(currentTickerPrices);
        lastPnls.putAll(currentPnLMap);
    }

    // 🌟 新增：发送 WxPusher 通知
    private static void sendWxPusherNotification(PriceAlert alert, BigDecimal currentValue, BigDecimal displayValue) {
        String typeDisplay = alert.type;
        String title = "提醒触发";
        String valueLabel = "当前数值";
        String targetLabel = "目标数值";

        // 这里的 displayValue 是从调用方传来的“触发线”
        BigDecimal displayTarget = displayValue;

        if ("price_reached".equals(alert.type)) {
            typeDisplay = "价格到达";
            title = "🚨 价格提醒触发";
            valueLabel = "当前价格";
            targetLabel = "目标价格";
        } else if ("profit_reached".equals(alert.type)) {
            typeDisplay = "盈利达到";
            title = "💰 盈利提醒触发";
            valueLabel = "当前盈亏";
            targetLabel = "目标盈利";
        } else if ("loss_reached".equals(alert.type)) {
            typeDisplay = "亏损达到";
            title = "📉 亏损提醒触发";
            valueLabel = "当前盈亏";
            targetLabel = "目标亏损";
            // 对于 loss_reached，targetPrice 是正数显示的亏损额，所以 displayTarget 不用动，就是
            // alert.targetPrice
        } else if ("profit_step".equals(alert.type)) {
            typeDisplay = "每逢盈利";
            title = "🚀 每逢盈利提醒";
            valueLabel = "当前盈亏";
            targetLabel = "当前台阶";
        } else if ("loss_step".equals(alert.type)) {
            typeDisplay = "每逢亏损";
            title = "⚠️ 每逢亏损提醒";
            valueLabel = "当前盈亏";
            targetLabel = "当前台阶";
        }

        String scope = (alert.symbol == null || alert.symbol.isEmpty()) ? "全账户" : alert.symbol;
        String content = "<h1>" + title + "</h1>" +
                "<p><b>监控对象:</b> " + scope + "</p>" +
                "<p><b>提醒类型:</b> " + typeDisplay + "</p>" +
                "<p><b>" + targetLabel + ":</b> <span style='color:blue'>" + displayTarget + "</span></p>" +
                "<p><b>" + valueLabel + ":</b> <span style='color:red'>" + currentValue + "</span></p>" +
                "<p><b>时间:</b> " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "</p>";

        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        // summary 也修正为显示 displayTarget
        body.addProperty("summary", typeDisplay + "提醒: " + scope + " 达到 " + displayTarget);
        body.addProperty("contentType", 2); // HTML
        body.addProperty("spt", WX_PUSHER_SPT);

        httpPost(WX_PUSHER_URL, body.toString());
    }

    // 🌟 新增：通用的 HTTP POST 方法
    private static String httpPost(String urlStr, String jsonBody) {
        try {
            URL url = new URL(urlStr);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonBody.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null)
                        sb.append(line);
                    return sb.toString();
                }
            } else {
                System.out.println("HTTP POST 错误: " + responseCode);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static String httpGet(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null)
                sb.append(line);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // 🌟 新增：带 API Key 头部的 HTTP GET 请求（用于需要认证的接口）
    private static String httpGetWithApiKey(String urlStr) {
        HttpsURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("X-MBX-APIKEY", BINANCE_API_KEY);

            int responseCode = conn.getResponseCode();

            // 读取响应
            InputStream is = (responseCode >= 200 && responseCode < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            if (is == null) {
                return "{\"error\":\"No response from server, HTTP " + responseCode + "\"}";
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null)
                sb.append(line);
            br.close();

            String response = sb.toString();

            // 如果是错误响应，包装成错误格式
            if (responseCode >= 400) {
                System.out.println("Binance API 错误 (HTTP " + responseCode + "): " + response);
                return "{\"error\":\"Binance API error: " + response.replace("\"", "\\\"") + "\"}";
            }

            return response;
        } catch (Exception e) {
            e.printStackTrace();
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // 🌟 新增：生成 HMAC SHA256 签名
    private static String hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hash = mac.doFinal(data.getBytes("UTF-8"));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1)
                hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    // 🌟 新增：带签名的 Binance API 请求
    private static String httpGetWithSignature(String baseUrl, String queryParams) {
        try {
            // 添加 timestamp
            long timestamp = System.currentTimeMillis();
            String params = (queryParams != null && !queryParams.isEmpty())
                    ? queryParams + "&timestamp=" + timestamp
                    : "timestamp=" + timestamp;

            // 生成签名
            String signature = hmacSha256(params, BINANCE_SECRET_KEY);
            params += "&signature=" + signature;

            String fullUrl = baseUrl + "?" + params;

            URL url = new URL(fullUrl);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("X-MBX-APIKEY", BINANCE_API_KEY);

            int responseCode = conn.getResponseCode();

            InputStream is = (responseCode >= 200 && responseCode < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            if (is == null) {
                return "{\"error\":\"No response from server, HTTP " + responseCode + "\"}";
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null)
                sb.append(line);
            br.close();

            String response = sb.toString();

            if (responseCode >= 400) {
                System.out.println("Binance API 错误 (HTTP " + responseCode + "): " + response);
                return "{\"error\":\"Binance API error: " + response.replace("\"", "\\\"") + "\"}";
            }

            return response;
        } catch (Exception e) {
            e.printStackTrace();
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private static void initProxy() {
        String isProxy = System.getenv("is_proxy");
        System.out.println("当前代理状态：" + isProxy);
        if ("false".equals(isProxy)) {
            return;
        }
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", "7897");
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", "7897");
    }

    private static void saveDcaSettingsToFile(String json) {
        try (PrintWriter out = new PrintWriter(new FileWriter(DCA_FILE_PATH))) {
            out.print(json);
            System.out.println("DCA 配置已保存至文件");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void loadDcaSettingsFromFile() {
        File file = new File(DCA_FILE_PATH);
        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                dcaSettingsCache = sb.toString();
                System.out.println("已从文件加载 DCA 配置");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // 🌟 新增：价格提醒文件持久化
    private static void savePriceAlertsToFile() {
        try (PrintWriter out = new PrintWriter(new FileWriter(PRICE_ALERT_FILE_PATH))) {
            out.print(new GsonBuilder().setPrettyPrinting().create().toJson(priceAlerts));
            System.out.println("价格提醒配置已保存至文件");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void loadPriceAlertsFromFile() {
        File file = new File(PRICE_ALERT_FILE_PATH);
        System.out.println("正在尝试加载价格提醒文件: " + file.getAbsolutePath());
        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                Gson gson = new Gson();
                PriceAlert[] alerts = gson.fromJson(reader, PriceAlert[].class);
                if (alerts != null) {
                    priceAlerts = new CopyOnWriteArrayList<>(Arrays.asList(alerts));
                    System.out.println("✅ 成功从文件加载 " + priceAlerts.size() + " 条价格提醒");
                } else {
                    System.out.println("⚠️ 价格提醒文件存在但解析为空");
                }
            } catch (Exception e) {
                System.err.println("❌ 加载价格提醒文件时发生错误");
                e.printStackTrace();
            }
        } else {
            System.out.println("ℹ️ 价格提醒文件不存在，将使用空列表");
        }
    }
}
