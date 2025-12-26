package org.example;

public class Main {
    public static void main(String[] args) throws Exception {
        initProxy();
        BinanceCombinedServer.main(args);
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
}