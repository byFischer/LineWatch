package com.emre.linewatch.collector;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Archer VR400'den WAN_DSL_INTF_CFG + WAN_DSL_INTF_STATS_TOTAL okur. */
public class TpLinkCollector implements ModemCollector {

    private static final String DSL_PAYLOAD = "1&1\r\n"
            + "[WAN_DSL_INTF_CFG#1,0,0,0,0,0#0,0,0,0,0,0]0,12\r\n"
            + "status\r\nmodulationType\r\nupstreamCurrRate\r\ndownstreamCurrRate\r\n"
            + "downstreamNoiseMargin\r\nupstreamNoiseMargin\r\ndownstreamAttenuation\r\nupstreamAttenuation\r\n"
            + "downstreamPower\r\nupstreamPower\r\ndownstreamMaxRate\r\nupstreamMaxRate\r\n"
            + "[WAN_DSL_INTF_STATS_TOTAL#1,0,0,0,0,0#0,0,0,0,0,0]1,8\r\n"
            + "CRCErrors\r\nATUCCRCErrors\r\nFECErrors\r\nATUCFECErrors\r\n"
            + "HECErrors\r\nATUCHECErrors\r\nerroredSecs\r\nseverelyErroredSecs\r\n";

    private final TpLinkGdprClient client;

    public TpLinkCollector(String host, String password) {
        this.client = new TpLinkGdprClient(host, password);
    }

    @Override
    public LineSample collect() throws CollectorException {
        String plain;
        try {
            plain = client.act(DSL_PAYLOAD);
        } catch (IOException | InterruptedException first) {
            // Oturum dusmus olabilir (kullanici tarayicidan girdi vs.) -> bir kez yeniden login
            client.invalidateSession();
            try {
                plain = client.act(DSL_PAYLOAD);
            } catch (IOException | InterruptedException e) {
                throw classify(e);
            }
        }
        return parse(plain);
    }

    private CollectorException classify(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        CollectorException.Kind kind;
        if (msg.contains("ulasilamiyor") || msg.contains("timed out")) {
            kind = CollectorException.Kind.UNREACHABLE;
        } else if (msg.contains("Login reddedildi")) {
            kind = CollectorException.Kind.AUTH_FAILED;
        } else {
            kind = CollectorException.Kind.PROTOCOL;
        }
        return new CollectorException(kind, msg, e);
    }

    private LineSample parse(String plain) throws CollectorException {
        Map<String, String> v = new HashMap<>();
        for (String line : plain.split("\r?\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("[")) {
                v.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        if (!v.containsKey("status")) {
            throw new CollectorException(CollectorException.Kind.PROTOCOL,
                    "Yanit cozumlenemedi: " + plain.substring(0, Math.min(200, plain.length())), null);
        }
        return new LineSample(
                System.currentTimeMillis(),
                v.getOrDefault("status", "?"),
                v.getOrDefault("modulationType", "?"),
                i(v, "downstreamCurrRate"), i(v, "upstreamCurrRate"),
                i(v, "downstreamMaxRate"), i(v, "upstreamMaxRate"),
                i(v, "downstreamNoiseMargin"), i(v, "upstreamNoiseMargin"),
                i(v, "downstreamAttenuation"), i(v, "upstreamAttenuation"),
                i(v, "downstreamPower"), i(v, "upstreamPower"),
                l(v, "CRCErrors"), l(v, "ATUCCRCErrors"),
                l(v, "FECErrors"), l(v, "ATUCFECErrors"),
                l(v, "HECErrors"), l(v, "ATUCHECErrors"),
                l(v, "erroredSecs"), l(v, "severelyErroredSecs"));
    }

    private static int i(Map<String, String> v, String k) {
        try { return Integer.parseInt(v.getOrDefault(k, "0")); } catch (NumberFormatException e) { return 0; }
    }

    private static long l(Map<String, String> v, String k) {
        try { return Long.parseLong(v.getOrDefault(k, "0")); } catch (NumberFormatException e) { return 0; }
    }

    @Override
    public void reboot() throws CollectorException {
        try {
            client.reboot();
        } catch (Exception e) {
            throw classify(e);
        }
    }

    @Override
    public void close() {
        client.logout();
    }
}
