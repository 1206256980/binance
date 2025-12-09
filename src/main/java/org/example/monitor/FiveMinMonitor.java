package org.example.monitor;

// Java Maven project - Full implementation for Binance USDT perpetual futures 5m Kline monitor
// JDK 8
// 功能：
// 1. 每 40 秒请求一次全量 U 本位合约交易对（通过 exchangeInfo 自动获取全部 USDT 合约符号）
// 2. 获取每个 symbol 最近 2 根 5m K 线
// 3. 计算涨幅 >=4% 的币加入监控列表，记录触发价 (lastClose * 0.98)
// 4. 持续检查后续轮询中是否跌破触发价，并统计在第几根 5m 回落
// 5. 输出统计信息


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import okhttp3.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FiveMinMonitor {

    private static final String KLINE_URL = "https://fapi.binance.com/fapi/v1/klines";
    private static final String EXCHANGE_INFO_URL = "https://fapi.binance.com/fapi/v1/exchangeInfo";

    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 监控符号：symbol -> MonitorItem
    private static final Map<String, MonitorItem> monitorMap = new ConcurrentHashMap<>();

    // 回落统计
    private static final Map<Integer, Integer> fallBackStats = new ConcurrentHashMap<>();
    private static int totalGreater4 = 0;

    public static void main(String[] args) {
        // 启动统计任务
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleAtFixedRate(() -> {
            try {
                processAllSymbols(); // 统计逻辑
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 40, TimeUnit.SECONDS);

        // 启动 HTTP 接口服务
        startHttpServer();
    }

    //=============================== 获取全部 USDT 合约交易对 ===============================
    private static List<String> getAllUsdtSymbols() throws IOException {
        Request req = new Request.Builder().url(EXCHANGE_INFO_URL).get().build();
        Response resp = CLIENT.newCall(req).execute();
        if (!resp.isSuccessful()) return Collections.emptyList();

        Map<String, Object> data = MAPPER.readValue(resp.body().string(), new TypeReference<Map<String, Object>>() {});
        List<Map<String, Object>> symbols = (List<Map<String, Object>>) data.get("symbols");

        List<String> result = new ArrayList<>();
        for (Map<String, Object> s : symbols) {
            if ("USDT".equals(s.get("quoteAsset")) && "PERPETUAL".equals(s.get("contractType"))) {
                result.add(s.get("symbol").toString());
            }
        }
        return result;
    }

    //=============================== 请求 5mK ===============================
    private static List<List<Object>> fetchKline(String symbol) throws IOException {
        HttpUrl url = HttpUrl.parse(KLINE_URL).newBuilder()
                .addQueryParameter("symbol", symbol)
                .addQueryParameter("interval", "5m")
                .addQueryParameter("limit", "2")
                .build();

        Request request = new Request.Builder().url(url).get().build();
        Response response = CLIENT.newCall(request).execute();
        if (!response.isSuccessful()) return Collections.emptyList();
        String result = response.body().string();
        System.out.println(result);
        return MAPPER.readValue(result, new TypeReference<List<List<Object>>>() {});
    }

    //=============================== 主处理逻辑 ===============================
    private static void processAllSymbols() throws Exception {

        List<String> symbols = getAllUsdtSymbols();

        for (String symbol : symbols) {
            List<List<Object>> k = fetchKline(symbol);
            if (k.size() < 2) continue;

            double prevClose = Double.parseDouble(k.get(0).get(4).toString());
            double lastClose = Double.parseDouble(k.get(1).get(4).toString());

            double rise = (lastClose - prevClose) / prevClose * 100.0;

            // 出现涨幅 ≥ 4%，加入监控
            if (rise >= 4 && !monitorMap.containsKey(symbol)) {
                totalGreater4++;

                MonitorItem item = new MonitorItem();
                item.symbol = symbol;
                item.triggerPrice = lastClose * 0.98;
                item.startIndex = 0;

                monitorMap.put(symbol, item);
                System.out.println("加入监控: " + symbol + " 涨幅=" + rise + "% 触发价=" + item.triggerPrice);
            }

            // 监控中检查是否触发
            if (monitorMap.containsKey(symbol)) {
                MonitorItem item = monitorMap.get(symbol);
                item.startIndex++;

                if (lastClose <= item.triggerPrice) {
                    int index = item.startIndex;
                    fallBackStats.put(index, fallBackStats.getOrDefault(index, 0) + 1);
                    System.out.println(symbol + " 在第 " + index + " 根 5m 回落到触发价");
                    monitorMap.remove(symbol);
                }
            }
        }
        printStats(); // 控制台输出
    }

    private static void printStats() {
        System.out.println("================= 统计结果 =================");
        System.out.println("满足 ≥4% 涨幅的总数: " + totalGreater4);
        for (Map.Entry<Integer, Integer> e : fallBackStats.entrySet()) {
            System.out.println(e.getKey() + " 根 5m 回落数量 = " + e.getValue());
        }
        System.out.println("=============================================");
    }

    //=============================== 数据结构 ===============================
    static class MonitorItem {
        String symbol;
        double triggerPrice;
        int startIndex;
    }
    //============================ HTTP 接口 ============================
    private static void startHttpServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

            // 获取完整统计数据
            server.createContext("/stats", exchange -> {
                String json = MAPPER.writeValueAsString(buildStatsJson());
                exchange.sendResponseHeaders(200, json.getBytes().length);
                exchange.getResponseBody().write(json.getBytes());
                exchange.close();
            });

            // 当前监控中的币
            server.createContext("/monitoring", exchange -> {
                String json = MAPPER.writeValueAsString(monitorMap);
                exchange.sendResponseHeaders(200, json.getBytes().length);
                exchange.getResponseBody().write(json.getBytes());
                exchange.close();
            });

            server.start();
            System.out.println("HTTP API 已启动：http://localhost:8080/stats");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Map<String, Object> buildStatsJson() {
        Map<String, Object> map = new HashMap<>();
        map.put("totalGreater4", totalGreater4);
        map.put("fallBackStats", fallBackStats);
        map.put("monitoring", monitorMap.keySet());
        return map;
    }
}

