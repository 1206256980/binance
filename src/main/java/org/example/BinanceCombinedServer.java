package org.example;

import com.google.gson.*;
import spark.Spark;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class BinanceCombinedServer {

    // ------------------- 公共配置 -------------------
    private static final String EXCHANGE_INFO_URL = "https://fapi.binance.com/fapi/v1/exchangeInfo";
    private static final String KLINES_URL = "https://fapi.binance.com/fapi/v1/klines";
    private static final int THREADS = 100;
    private static final int DEFAULT_REFRESH_SECONDS = 15;
    private static final String[] INTERVALS = {"5m","10m","15m","30m","40m","50m","60m"};
    private static final int TOP_CHANGE = 10;
    private static final int TOP_AMPLITUDE = 10;
    private static final int KLINE_COUNT = 12; // 取最近 12 根 5m K 线
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(THREADS);

    // ------------------- 缓存 -------------------
    private static volatile Map<String, List<CandleRaw>> klineCache = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, List<Candle>>> rankCache = new LinkedHashMap<>();
    private static volatile List<String> strongCache = new ArrayList<>();

    // ------------------- 数据模型 -------------------
    static class CandleRaw {
        BigDecimal open, high, low, close, volume;
        CandleRaw(BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c, BigDecimal v) {
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

    private static volatile List<String> cachedSymbols = new ArrayList<>();
    private static volatile long cachedSymbolsTime = 0;
    private static final long SYMBOLS_CACHE_DURATION = 10 * 60 * 1000; // 10分钟


    // 强势币使用的 K 线根数（6 根 5m -> 30 分钟）
    private static final int STRONG_KLINE_COUNT = 6;

    public static void main(String[] args) throws Exception {
//        initProxy();
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
                List<CandleRaw> klines = fetch5mKlines(symbol, KLINE_COUNT);
                if (klines != null && !klines.isEmpty()) newKlineCache.put(symbol, klines);
            }, EXECUTOR));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long used = System.currentTimeMillis() - start;
        System.out.println(ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))+"----全部请求完成，耗时：" + used + "ms");

        klineCache = newKlineCache;

        // ---------------- 排行榜逻辑 ----------------
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

        // ---------------- 强势币逻辑（使用最近 STRONG_KLINE_COUNT 根 5m K 线） ----------------
        List<String> strongs = new ArrayList<>();
        for (String symbol : symbols) {
            List<CandleRaw> rawsAll = klineCache.get(symbol);
            if (rawsAll == null || rawsAll.size() < STRONG_KLINE_COUNT) continue;

            // 取最近 STRONG_KLINE_COUNT 根
            List<CandleRaw> lastN = rawsAll.subList(rawsAll.size() - STRONG_KLINE_COUNT, rawsAll.size());

            // 计算区间最低、最高、当前价
            BigDecimal lowMin = lastN.stream().map(c -> c.low).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal highMax = lastN.stream().map(c -> c.high).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal current = lastN.get(lastN.size() - 1).close;

            BigDecimal denominator = highMax.subtract(lowMin);
            if (denominator.compareTo(BigDecimal.ZERO) == 0) {
                continue; // 无波动，跳过
            }
            BigDecimal posRatio = current.subtract(lowMin).divide(denominator, 8, RoundingMode.HALF_UP);

            // 累计涨幅基于 lastN 的第一根 open（最近 STRONG_KLINE_COUNT 根的区间）
            BigDecimal firstOpen = lastN.get(0).open;
            if (firstOpen.compareTo(BigDecimal.ZERO) == 0) continue; // 防止除0
            BigDecimal cumChange = current.subtract(firstOpen).divide(firstOpen, 8, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));

            // 成交额基于 lastN 的成交量 * 收盘价
            BigDecimal maxVol = lastN.stream()
                    .map(r -> r.volume.multiply(r.close))
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            if (posRatio.compareTo(new BigDecimal("0.7")) >= 0 &&
                    cumChange.compareTo(new BigDecimal("8")) >= 0) {
                strongs.add(symbol);
            }
        }
        strongCache = strongs;
    }

    private static Candle aggregate(String symbol, List<CandleRaw> raws) {
        BigDecimal open = raws.get(0).open;
        BigDecimal close = raws.get(raws.size() - 1).close;
        BigDecimal high = raws.stream().map(r -> r.high).max(BigDecimal::compareTo).get();
        BigDecimal low = raws.stream().map(r -> r.low).min(BigDecimal::compareTo).get();
        return new Candle(symbol, open, high, low, close);
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
                BigDecimal open = k.get(1).getAsBigDecimal();
                BigDecimal high = k.get(2).getAsBigDecimal();
                BigDecimal low = k.get(3).getAsBigDecimal();
                BigDecimal close = k.get(4).getAsBigDecimal();
                BigDecimal volume = k.get(5).getAsBigDecimal();
                list.add(new CandleRaw(open, high, low, close, volume));
            }
            return list;
        } catch (Exception e) {
            return Collections.emptyList();
        }
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
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", "7897");
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", "7897");
    }
}



