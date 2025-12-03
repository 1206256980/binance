package org.example;

import com.google.gson.*;
import spark.Spark;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import spark.Spark;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class BinanceCombinedServer {

    // ------------------- 公共配置 -------------------
    private static final String EXCHANGE_INFO_URL = "https://fapi.binance.com/fapi/v1/exchangeInfo";
    private static final String KLINES_URL = "https://fapi.binance.com/fapi/v1/klines";
    private static final int THREADS = 100;
    private static final int DEFAULT_REFRESH_SECONDS = 25;
    private static final String[] INTERVALS = {"5m","10m","15m","30m","40m","50m","60m"};
    private static final int TOP_CHANGE = 20;
    private static final int TOP_AMPLITUDE = 20;
    private static final int KLINE_COUNT = 12; // 取最近 12 根 5m K 线
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(THREADS);

    // 🌟 新增配置：指数文件路径
    private static final String INDEX_FILE_PATH = "public/alt_futures_index_history.json";
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

    // ------------------- 数据模型 -------------------
    static class CandleRaw {
        BigDecimal open, high, low, close, volume;
        long openTime; // 🌟 增加时间点，用于指数时间戳
        CandleRaw(long ot, BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c, BigDecimal v) {
            this.openTime = ot;
            open = o; high = h; low = l; close = c; volume = v;
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
        StrongCoin(String s) { symbol = s; }
    }

    // 🌟 新增数据模型：指数历史点
    static class IndexPoint {
        long timestamp; // 时间戳 (毫秒)
        BigDecimal value; // AltFuturesIndex 值

        public IndexPoint(long timestamp, BigDecimal value) {
            this.timestamp = timestamp;
            this.value = value.setScale(4, RoundingMode.HALF_UP); // 保留 4 位小数
        }
    }

    // 🌟 新增数据模型：用于指数计算时的排序和暂存
    private static class IndexData {
        String symbol;
        BigDecimal change;     // Delta P_i (30分钟价格变动百分比)
        BigDecimal tradeValue; // V_i (30分钟总成交额 / 交易价值)

        public IndexData(String symbol, BigDecimal change, BigDecimal tradeValue) {
            this.symbol = symbol;
            this.change = change;
            this.tradeValue = tradeValue;
        }
    }


    private static volatile List<String> cachedSymbols = new ArrayList<>();
    private static volatile long cachedSymbolsTime = 0;
    private static final long SYMBOLS_CACHE_DURATION = 60 * 60 * 1000; // 10分钟


    // 强势币使用的 K 线根数（6 根 5m -> 30 分钟）
    private static final int STRONG_KLINE_COUNT = 6;

    public static void main(String[] args) throws Exception {
        initProxy();
        loadIndexHistory(); // 🌟 启动时加载历史数据
        Spark.port(4567);
        Spark.staticFiles.location("/public");

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                refreshAllData();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, DEFAULT_REFRESH_SECONDS, TimeUnit.SECONDS);

        Spark.get("/data", (req, res) -> {
            res.type("application/json; charset=UTF-8");
            return new GsonBuilder().setPrettyPrinting().create().toJson(rankCache);
        });

        Spark.get("/strong", (req, res) -> {
            res.type("application/json; charset=UTF-8");
            return new GsonBuilder().setPrettyPrinting().create()
                    .toJson(strongCache.stream().map(StrongCoin::new).collect(Collectors.toList()));
        });

        // 🌟 新增 API 接口：获取指数历史数据
        Spark.get("/index_history", (req, res) -> {
            res.type("application/json; charset=UTF-8");
            return new Gson().toJson(indexHistoryCache);
        });
    }

    // ------------------- 刷新逻辑 -------------------
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
                if (klines != null && !klines.isEmpty()) newKlineCache.put(symbol, klines);
            }, EXECUTOR));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long used = System.currentTimeMillis() - start;
        System.out.println("全部请求完成，耗时：" + used + "ms");

        klineCache = newKlineCache;

        // ---------------- 🌟 指数计算频率控制 🌟 ----------------
        long now = System.currentTimeMillis();

        // 判断是否超过 3 分钟的计算间隔
        if (now - lastIndexCalculationTime >= INDEX_CALCULATION_INTERVAL_MS) {

            System.out.println("--- Starting 3-minute Alt Index calculation ---");
            // 调用指数计算函数
            BigDecimal altFuturesIndex = calculateAltFuturesIndex(klineCache);

            // 指数计算完成后，保存到历史缓存和本地文件
            if (altFuturesIndex != null) {
                IndexPoint newPoint = new IndexPoint(now, altFuturesIndex);
                saveIndexPoint(newPoint);
                System.out.println("Alt Index calculated and saved: " + newPoint.value.toPlainString());
            }

            // 更新上次计算时间，确保下次计算至少在 3 分钟之后
            lastIndexCalculationTime = now;
        }

        // ---------------- 排行榜逻辑 ---------------- (代码保持不变，省略以保持简洁，但请在您的文件中保留)
        // ... (原有的排行榜逻辑)
        Map<String, Map<String,Candle>> allMap = new ConcurrentHashMap<>();
        for(String symbol: klineCache.keySet()){
            List<CandleRaw> klines = klineCache.get(symbol);
            if(klines==null || klines.isEmpty()) continue;
            Map<String,Candle> map = new HashMap<>();

            // 遍历所有 INTERVALS，计算需要多少根 5m K 线
            for(String interval: INTERVALS){
                int minutes = Integer.parseInt(interval.replace("m",""));
                int needed = minutes / 5;
                if(klines.size() >= needed){
                    List<CandleRaw> sub = klines.subList(klines.size()-needed, klines.size());
                    map.put(interval, aggregate(symbol, sub));
                }
            }
            allMap.put(symbol,map);
        }

        // 构建排行榜
        for(String interval: INTERVALS){
            List<Candle> candles = new ArrayList<>();
            for(String symbol: symbols){
                Map<String,Candle> m = allMap.get(symbol);
                if(m!=null && m.containsKey(interval)){
                    Candle c = m.get(interval);
                    Map<String, Map<String, BigDecimal>> others = new HashMap<>();
                    for(String i2: INTERVALS){
                        Candle c2 = m.get(i2);
                        if(c2!=null){
                            Map<String,BigDecimal> map2 = new HashMap<>();
                            map2.put("change",c2.change);
                            map2.put("amplitude",c2.amplitude);
                            others.put(i2,map2);
                        }
                    }
                    c.others = others;
                    candles.add(c);
                }
            }
            Map<String,List<Candle>> intervalMap = new HashMap<>();
            intervalMap.put("change", candles.stream()
                    .sorted((a,b)->b.change.compareTo(a.change))
                    .limit(TOP_CHANGE)
                    .collect(Collectors.toList()));
            intervalMap.put("amplitude", candles.stream()
                    .sorted((a, b) -> b.amplitude.compareTo(a.amplitude))
                    .limit(TOP_AMPLITUDE)
                    .collect(Collectors.toList()));
            rankCache.put(interval, intervalMap);
        }


        // ---------------- 强势币逻辑 ---------------- (代码保持不变，省略以保持简洁，但请在您的文件中保留)
        List<String> strongs = new ArrayList<>();
        // ... (原有的强势币逻辑，这里不再重复粘贴)

        for (String symbol : symbols) {
            List<CandleRaw> rawsAll = klineCache.get(symbol);
            if (rawsAll == null || rawsAll.size() < STRONG_KLINE_COUNT) continue;

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
                    cumChange.compareTo(new BigDecimal("8")) >= 0) {
                isComboOne = true;
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
                boolean volumeCondition = currentVolume.compareTo(previousMaxVolume.multiply(new BigDecimal("4.0"))) >= 0;//成交量4倍量爆量

                // Condition 2: Price Surge (5m Change >= 5%)
                boolean surgeCondition = current5mChange.compareTo(new BigDecimal("5")) >= 0;//涨幅大于5

                if (volumeCondition && surgeCondition) {
                    isVolumeSpikeAndSurge = true;
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


    // ------------------- 新增：AltFuturesIndex 计算函数 -------------------

    private static BigDecimal calculateAltFuturesIndex(Map<String, List<CandleRaw>> klineMap) {
        List<IndexData> indexDataList = new ArrayList<>();

        // 1. 数据收集与预计算 (遍历所有 altcoin，计算 30m 交易额和涨跌幅)
        for (Map.Entry<String, List<CandleRaw>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();

            // 排除 BTC 和 ETH
            if (symbol.equals("BTCUSDT") || symbol.equals("ETHUSDT")) {
                continue;
            }

            List<CandleRaw> rawsAll = entry.getValue();
            if (rawsAll == null || rawsAll.size() < INDEX_KLINE_COUNT) {
                continue;
            }
            List<CandleRaw> lastN = rawsAll.subList(rawsAll.size() - INDEX_KLINE_COUNT, rawsAll.size());

            // 30m 总成交额 (Sum of Volume * Close over 6 candles)
            BigDecimal totalTradeValue = lastN.stream()
                    .map(c -> c.volume.multiply(c.close))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 30m 价格变动 Delta P_i (累计涨跌幅百分比)
            BigDecimal firstOpen = lastN.get(0).open;
            BigDecimal lastClose = lastN.get(INDEX_KLINE_COUNT - 1).close;
            BigDecimal deltaP = BigDecimal.ZERO;

            if (firstOpen.compareTo(BigDecimal.ZERO) > 0) {
                deltaP = lastClose.subtract(firstOpen)
                        .multiply(new BigDecimal("100"))
                        .divide(firstOpen, 4, RoundingMode.HALF_UP);
            }

            indexDataList.add(new IndexData(symbol, deltaP, totalTradeValue));
        }

        // 2. 筛选与排序: 按 30m 总成交额降序，选取 Top 50
        List<IndexData> topNIndexData = indexDataList.stream()
                // 排序依据：tradeValue 降序
                .sorted(Comparator.comparing(d -> d.tradeValue, Comparator.reverseOrder()))
                .limit(INDEX_POOL_SIZE) // 截取 Top 50
                .collect(Collectors.toList());

        if (topNIndexData.isEmpty()) return BigDecimal.ZERO;

        // 3. 指数计算 (成交额加权平均)
        BigDecimal altFuturesIndex = BigDecimal.ZERO;

        // 计算 Top N 池的总成交额 (Sum V_j)
        BigDecimal poolTotalTradeValue = topNIndexData.stream()
                .map(d -> d.tradeValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 计算加权指数
        if (poolTotalTradeValue.compareTo(BigDecimal.ZERO) > 0) {
            for (IndexData data : topNIndexData) {
                // 权重 W_i = V_i / Sum(V_j)
                BigDecimal weight = data.tradeValue.divide(poolTotalTradeValue, 8, RoundingMode.HALF_UP);

                // 加权变动 = W_i * Delta P_i
                BigDecimal weightedChange = weight.multiply(data.change);

                altFuturesIndex = altFuturesIndex.add(weightedChange);
            }
            return altFuturesIndex;
        }

        return BigDecimal.ZERO;
    }

    // ------------------- 新增：指数历史数据处理 -------------------

    /**
     * 将最新的指数点保存到缓存和本地文件
     */
    private static synchronized void saveIndexPoint(IndexPoint point) {
        indexHistoryCache.add(point);
        // 保持缓存数据量在一个合理范围
        if (indexHistoryCache.size() > 1000) {
            indexHistoryCache.remove(0);
        }

        // 🌟 关键修改：在写入文件前，检查并创建父目录 (public)
        File file = new File(INDEX_FILE_PATH);
        File parentDir = file.getParentFile();

        // 确保父目录存在，如果不存在则创建
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        // 写入本地文件
        try (FileWriter writer = new FileWriter(file)) {
            new Gson().toJson(indexHistoryCache, writer);
        } catch (Exception e) {
            System.err.println("Failed to write index history to file: " + e.getMessage());
        }
    }

    /**
     * 启动时从本地文件加载历史指数数据
     */
    private static void loadIndexHistory() {
        File file = new File(INDEX_FILE_PATH);
        if (!file.exists()) {
            System.out.println("Index history file not found, starting with empty history.");
            return;
        }

        try (FileReader reader = new FileReader(file)) {
            Gson gson = new Gson();
            // 使用 TypeToken 或直接使用 List<IndexPoint>.class (如果结构简单)
            IndexPoint[] historyArray = gson.fromJson(reader, IndexPoint[].class);
            if (historyArray != null) {
                indexHistoryCache = new ArrayList<>(Arrays.asList(historyArray));
                System.out.println("Loaded " + indexHistoryCache.size() + " index points from file.");
            }
        } catch (Exception e) {
            System.err.println("Failed to load index history: " + e.getMessage());
            indexHistoryCache = new ArrayList<>(); // 加载失败，清空缓存
        }
    }


    // ------------------- 工具方法 -------------------
    private static List<String> getAllSymbolsCached() throws Exception {
        long now = System.currentTimeMillis();
        if (!cachedSymbols.isEmpty() && (now - cachedSymbolsTime < SYMBOLS_CACHE_DURATION)) {
            return cachedSymbols;
        }

        String json = httpGet(EXCHANGE_INFO_URL);
        if (json == null || json.isEmpty()) return Collections.emptyList();
        Gson gson = new Gson();
        JsonObject obj = gson.fromJson(json, JsonObject.class);
        JsonArray arr = obj.getAsJsonArray("symbols");

        List<String> list = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject symObj = el.getAsJsonObject();
            String symbol = symObj.get("symbol").getAsString();
            if (symbol.endsWith("USDT")) list.add(symbol);
        }

        // 去重
        cachedSymbols = new ArrayList<>(new LinkedHashSet<>(list));
        System.out.println("一共获取到"+cachedSymbols.size()+"个交易对");
        cachedSymbolsTime = now;

        return cachedSymbols;
    }


    private static List<CandleRaw> fetch5mKlines(String symbol, int limit) {
        try {
            long start = System.currentTimeMillis();
            String url = KLINES_URL + "?symbol=" + symbol + "&interval=5m&limit=" + limit;
            String json = httpGet(url);
            long end =System.currentTimeMillis() - start;
            System.out.println("接口返回,symbol:"+symbol+"耗时：" + end + ",json:"+json);
            System.out.println("-------------------------------------------");
            if (json == null || json.isEmpty()) return Collections.emptyList();
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
            e.printStackTrace();
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
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static void initProxy() {
        String isProxy = System.getenv("is_proxy");
        System.out.println("当前代理状态："+isProxy);
        if ("false".equals(isProxy)) {
            return;
        }
//        System.setProperty("http.proxyHost", "127.0.0.1");
//        System.setProperty("http.proxyPort", "7897");
//        System.setProperty("https.proxyHost", "127.0.0.1");
//        System.setProperty("https.proxyPort", "7897");
    }
}



