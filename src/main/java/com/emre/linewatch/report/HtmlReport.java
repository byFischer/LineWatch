package com.emre.linewatch.report;

import com.emre.linewatch.collector.LineSample;
import com.emre.linewatch.collector.NetSample;
import com.emre.linewatch.config.I18n;
import com.emre.linewatch.storage.LineEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Kanit raporunu tek, kendi kendine yeten bir HTML dosyasi olarak uretir.
 * Tarayicida acilir; "Yazdir -> PDF olarak kaydet" ile PDF'e donusturulur.
 *
 * <p>Metinler olcumleri oldugu gibi aktarir; hattin "kotu" oldugu gibi yargilar
 * yerine sayilar verilir ki rapor servis saglayiciya karsi savunulabilir olsun.
 */
public final class HtmlReport {

    static final int MAX_TABLE_ROWS = 300;
    private static final int MIN_SNR = com.emre.linewatch.storage.SampleRepository.MIN_VALID_SNR;
    /** Saat yogunlasmasi bulgusu icin pencere genisligi ve asgari olay sayisi. */
    private static final int WINDOW_HOURS = 4;
    private static final int MIN_EVENTS_FOR_WINDOW = 3;

    private static final LineEvent.Type[] OTHER_EVENTS = {
            LineEvent.Type.CRC_BURST, LineEvent.Type.LOW_SNR, LineEvent.Type.RETRAIN,
            LineEvent.Type.HIGH_LATENCY, LineEvent.Type.PACKET_LOSS,
            LineEvent.Type.MODEM_UNREACHABLE, LineEvent.Type.REBOOT};

    private final ReportData r;
    private final ZoneId zone;
    private final StringBuilder sb = new StringBuilder(64 * 1024);

    private HtmlReport(ReportData r, ZoneId zone) {
        this.r = r;
        this.zone = zone;
    }

    public static String render(ReportData data) {
        return render(data, ZoneId.systemDefault());
    }

    static String render(ReportData data, ZoneId zone) {
        return new HtmlReport(data, zone).build();
    }

    private String build() {
        String title = t("report.title");
        sb.append("<!doctype html><html lang='").append(I18n.locale().getLanguage()).append("'><head>")
                .append("<meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
                .append("<title>").append(esc(title)).append(" · ")
                .append(esc(date(r.from()))).append(" – ").append(esc(date(r.effectiveTo()))).append("</title>")
                .append("<style>").append(CSS).append("</style></head><body><main>");
        header(title);
        findings();
        figures();
        outages();
        otherEvents();
        charts();
        hourly();
        method();
        sb.append("<footer>").append(esc(t("report.footer"))).append("</footer></main></body></html>");
        return sb.toString();
    }

    // ---------- bolumler ----------

    private void header(String title) {
        sb.append("<header><div class='top'><h1>").append(esc(title)).append("</h1>")
                .append("<button class='noprint' onclick='window.print()'>").append(esc(t("report.print")))
                .append("</button></div><dl class='meta'>");
        meta(t("report.period"), dateTime(r.from()) + " – " + dateTime(r.effectiveTo()));
        meta(t("report.generated"), dateTime(r.generatedAt()));
        ReportOptions o = r.options();
        // Modem izleme sonradan kapatilmis olabilir; cihaz satiri araliktaki veriye gore yazilir.
        meta(t("report.device"), !r.hasLine() || o.device().isBlank() ? t("report.deviceNone")
                : o.device() + (r.line().modulation().isBlank() ? "" : " · " + r.line().modulation()));
        if (!o.subscriber().isBlank()) meta(t("report.subscriber"), o.subscriber());
        if (o.planMbps() > 0) meta(t("report.plan"), num(o.planMbps(), 0) + " Mbps");
        sb.append("</dl></header>");
    }

    private void meta(String key, String value) {
        sb.append("<div><dt>").append(esc(key)).append("</dt><dd>").append(esc(value)).append("</dd></div>");
    }

    private void findings() {
        List<String> items = new ArrayList<>();
        if (!r.hasLine() && !r.hasNet()) {
            items.add(t("report.fNoData"));
        }
        if (r.hasLine()) {
            List<Outage> dsl = r.dslOutages();
            if (dsl.isEmpty()) {
                items.add(t("report.fNoDrops"));
            } else {
                Outage longest = longest(dsl);
                items.add(longest == null
                        ? t("report.fDropsNoDuration", dsl.size())
                        : t("report.fDrops", dsl.size(), dur(knownTotal(dsl)), durMark(longest), dateTime(longest.start())));
            }
            addApproxNotes(items, dsl);
            window(r.dslDropsByHour()).ifPresent(w -> items.add(t("report.fDropWindow", w)));

            var line = r.line();
            if (line.hasUpData()) {
                if (line.minSnrDownDb() < r.options().snrThresholdDb()) {
                    items.add(t("report.fSnrLow", num(line.minSnrDownDb(), 1), num(r.options().snrThresholdDb(), 1)));
                }
                double plan = r.options().planMbps();
                if (plan > 0) {
                    double sync = line.avgSyncDown() / 1000;
                    items.add(t("report.fSync", num(sync, 1), num(plan, 0), pct(100 * sync / plan)));
                    double max = line.avgMaxDown() / 1000;
                    if (max > 0 && max < plan) items.add(t("report.fMaxBelowPlan", num(max, 1)));
                }
            }
            items.add(t("report.fCrc", num(r.crcTotal(), 0), num((double) r.crcTotal() / r.hours(), 0)));
            double[] crc = new double[24];
            for (int h = 0; h < 24; h++) crc[h] = r.crcByHour()[h];
            window(crc, r.crcTotal() > 0).ifPresent(w -> items.add(t("report.fCrcWindow", w)));
        }
        if (r.hasNet()) {
            var net = r.net();
            items.add(t("report.fNet", pct(net.onlinePct()), r.netOutages().size(), dur(knownTotal(r.netOutages()))));
            if (!r.hasLine()) addApproxNotes(items, r.netOutages());
            items.add(t("report.fNetQuality", net.avgLatencyMs() < 0 ? t("common.dash") : num(net.avgLatencyMs(), 0),
                    net.avgJitterMs() < 0 ? t("common.dash") : num(net.avgJitterMs(), 1), pct(net.avgLossPct())));
            if (!r.hasLine()) window(r.netDropsByHour()).ifPresent(w -> items.add(t("report.fNetWindow", w)));
        }
        double coverage = Math.max(r.hasLine() ? r.lineCoveragePct() : 0, r.hasNet() ? r.netCoveragePct() : 0);
        if ((r.hasLine() || r.hasNet()) && coverage < 90) items.add(t("report.fCoverage", pct(coverage)));

        sb.append("<section><h2>").append(esc(t("report.findings"))).append("</h2><ul class='findings'>");
        for (String item : items) sb.append("<li>").append(esc(item)).append("</li>");
        sb.append("</ul></section>");
    }

    private void figures() {
        if (!r.hasLine() && !r.hasNet()) return;
        sb.append("<section><h2>").append(esc(t("report.figures"))).append("</h2><div class='stats'>");
        if (r.hasLine()) {
            var line = r.line();
            List<Outage> dsl = r.dslOutages();
            stat(t("report.figDrops"), String.valueOf(dsl.size()), t("report.figDropsSub", dur(knownTotal(dsl))));
            Outage longest = longest(dsl);
            stat(t("report.figLongest"), longest == null ? t("common.dash") : durMark(longest),
                    longest == null ? "" : dateTime(longest.start()));
            if (line.hasUpData()) {
                stat(t("report.figSnr"), num(line.avgSnrDownDb(), 1) + " dB",
                        t("report.figSnrSub", num(line.minSnrDownDb(), 1)));
                double sync = line.avgSyncDown() / 1000;
                double plan = r.options().planMbps();
                stat(t("report.figSync"), num(sync, 1) + " Mbps", plan > 0
                        ? t("report.figSyncPlan", pct(100 * sync / plan))
                        : t("report.figSyncMax", num(line.avgMaxDown() / 1000, 1)));
            }
            stat(t("report.figCrc"), num(r.crcTotal(), 0),
                    t("report.figCrcSub", num((double) r.crcTotal() / r.hours(), 0)));
        }
        if (r.hasNet()) {
            var net = r.net();
            stat(t("report.figAvail"), pct(net.onlinePct()),
                    t("report.figAvailSub", r.netOutages().size(), dur(knownTotal(r.netOutages()))));
            stat(t("report.figLatency"), net.avgLatencyMs() < 0 ? t("common.dash") : num(net.avgLatencyMs(), 0) + " ms",
                    net.avgJitterMs() < 0 ? "" : t("report.figLatencySub", num(net.avgJitterMs(), 1)));
            stat(t("report.figLoss"), pct(net.avgLossPct()), t("report.figLossSub", num(net.samples(), 0)));
        }
        sb.append("</div></section>");
    }

    private void stat(String label, String value, String sub) {
        sb.append("<div class='stat'><div class='label'>").append(esc(label))
                .append("</div><div class='value'>").append(esc(value))
                .append("</div><div class='sub'>").append(esc(sub)).append("</div></div>");
    }

    private void outages() {
        List<Outage> all = new ArrayList<>(r.dslOutages());
        all.addAll(r.netOutages());
        all.sort(Comparator.comparingLong(Outage::start));
        sb.append("<section><h2>").append(esc(t("report.outages"))).append("</h2>");
        if (all.isEmpty()) {
            sb.append("<p class='muted'>").append(esc(t("report.noOutages"))).append("</p></section>");
            return;
        }
        sb.append("<table><thead><tr>");
        for (String key : new String[]{"report.colStart", "report.colEnd", "report.colDuration",
                "report.colType", "report.colDetail"}) {
            sb.append("<th>").append(esc(t(key))).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        for (Outage o : all.subList(0, Math.min(all.size(), MAX_TABLE_ROWS))) {
            String start = dateTime(o.start()) + (o.startedBefore() ? " " + t("report.startedBefore") : "");
            String end = !o.endKnown() ? t("report.endUnknown")
                    : o.approximate() ? t("report.endAtLatest", dateTime(o.end())) : dateTime(o.end());
            sb.append("<tr><td class='nowrap'>").append(esc(start))
                    .append("</td><td class='nowrap'>").append(esc(end))
                    .append("</td><td class='nowrap num'>").append(esc(o.durationKnown() ? durMark(o) : t("common.dash")))
                    .append("</td><td class='nowrap'><span class='kind ").append(o.kind() == Outage.Kind.DSL ? "dsl" : "net")
                    .append("'>").append(esc(t(o.kind() == Outage.Kind.DSL ? "report.kindDsl" : "report.kindNet")))
                    .append("</span></td><td class='detail'>").append(esc(o.detail())).append("</td></tr>");
        }
        sb.append("</tbody></table>");
        if (all.stream().anyMatch(Outage::approximate)) {
            sb.append("<p class='muted'>").append(esc(t("report.approxNote"))).append("</p>");
        }
        if (all.size() > MAX_TABLE_ROWS) {
            sb.append("<p class='muted'>").append(esc(t("report.truncated", MAX_TABLE_ROWS))).append("</p>");
        }
        sb.append("</section>");
    }

    private void otherEvents() {
        List<LineEvent.Type> present = new ArrayList<>();
        for (LineEvent.Type type : OTHER_EVENTS) {
            if (r.eventCounts().getOrDefault(type, 0) > 0) present.add(type);
        }
        if (present.isEmpty()) return;
        sb.append("<section><h2>").append(esc(t("report.otherEvents"))).append("</h2><table class='narrow'><thead><tr><th>")
                .append(esc(t("report.colEvent"))).append("</th><th class='num'>").append(esc(t("report.colCount")))
                .append("</th></tr></thead><tbody>");
        for (LineEvent.Type type : present) {
            sb.append("<tr><td>").append(esc(type.label())).append("</td><td class='num'>")
                    .append(r.eventCounts().get(type)).append("</td></tr>");
        }
        sb.append("</tbody></table></section>");
    }

    private void charts() {
        if (r.lineSeries().isEmpty() && r.netSeries().isEmpty()) return;
        sb.append("<section><h2>").append(esc(t("report.charts"))).append("</h2>");
        long from = r.from(), to = r.effectiveTo();
        List<LineSample> ls = r.lineSeries();
        if (!ls.isEmpty()) {
            int n = ls.size();
            long[] ts = new long[n];
            double[] snrDown = new double[n], snrUp = new double[n], sync = new double[n],
                    max = new double[n], crc = new double[n];
            for (int i = 0; i < n; i++) {
                LineSample s = ls.get(i);
                ts[i] = s.ts();
                // Hat kopukken modem 0 dondurur; grafikte sifira dusmek yerine bosluk birakilir.
                boolean up = s.syncDown() > 0;
                // -51 dB gibi gecersiz okumalar da bosluk olur (bkz. SampleRepository.MIN_VALID_SNR)
                snrDown[i] = up && s.snrDown() > MIN_SNR ? s.snrDown() / 10.0 : Double.NaN;
                snrUp[i] = up && s.snrUp() > MIN_SNR ? s.snrUp() / 10.0 : Double.NaN;
                sync[i] = up ? s.syncDown() / 1000.0 : Double.NaN;
                max[i] = up && s.maxRateDown() > 0 ? s.maxRateDown() / 1000.0 : Double.NaN;
                long delta = i == 0 ? 0 : s.crcDown() - ls.get(i - 1).crcDown();
                crc[i] = Math.max(0, delta);
            }
            figure(t("report.chartSnr"), legend(Svg.BLUE, "report.serDown", Svg.ORANGE, "report.serUp"),
                    Svg.lineChart(from, to, List.of(
                            new Svg.Series("", Svg.BLUE, ts, snrDown, false),
                            new Svg.Series("", Svg.ORANGE, ts, snrUp, false)),
                            List.of(new Svg.RefLine(t("report.serThreshold"), r.options().snrThresholdDb(), Svg.RED)),
                            "dB", zone));
            List<Svg.RefLine> syncRefs = r.options().planMbps() > 0
                    ? List.of(new Svg.RefLine(t("report.serPlan") + " " + num(r.options().planMbps(), 0) + " Mbps",
                    r.options().planMbps(), Svg.RED))
                    : List.of();
            figure(t("report.chartSync"), legend(Svg.BLUE, "report.serDown", Svg.GRAY, "report.serMax"),
                    Svg.lineChart(from, to, List.of(
                            new Svg.Series("", Svg.BLUE, ts, sync, false),
                            new Svg.Series("", Svg.GRAY, ts, max, true)), syncRefs, "Mbps", zone));
            figure(t("report.chartCrc"), "", Svg.timeBars(from, to, ts, crc, Svg.ORANGE, t("report.unitCount"), zone));
        }
        List<NetSample> ns = r.netSeries();
        if (!ns.isEmpty()) {
            int n = ns.size();
            long[] ts = new long[n];
            double[] lat = new double[n], jit = new double[n], loss = new double[n];
            for (int i = 0; i < n; i++) {
                NetSample s = ns.get(i);
                ts[i] = s.ts();
                lat[i] = s.hasLatency() ? s.latencyMs() : Double.NaN;
                jit[i] = s.hasJitter() ? s.jitterMs() : Double.NaN;
                loss[i] = s.lossPct();
            }
            figure(t("report.chartLatency"), legend(Svg.BLUE, "report.serLatency", Svg.PURPLE, "report.serJitter"),
                    Svg.lineChart(from, to, List.of(
                            new Svg.Series("", Svg.BLUE, ts, lat, false),
                            new Svg.Series("", Svg.PURPLE, ts, jit, false)), List.of(), "ms", zone));
            figure(t("report.chartLoss"), "", Svg.timeBars(from, to, ts, loss, Svg.RED, "%", zone));
        }
        sb.append("</section>");
    }

    private void hourly() {
        boolean crc = r.hasLine() && r.crcTotal() > 0;
        boolean dsl = sum(r.dslDropsByHour()) > 0;
        boolean net = sum(r.netDropsByHour()) > 0;
        if (!crc && !dsl && !net) return;
        sb.append("<section><h2>").append(esc(t("report.hourly"))).append("</h2>");
        if (crc) {
            double[] v = new double[24];
            for (int h = 0; h < 24; h++) v[h] = r.crcByHour()[h];
            figure(t("report.hourCrc"), "", Svg.hourBars(v, Svg.ORANGE, t("report.unitCount")));
        }
        if (dsl) figure(t("report.hourDsl"), "", Svg.hourBars(toDouble(r.dslDropsByHour()), Svg.RED, t("report.unitCount")));
        if (net) figure(t("report.hourNet"), "", Svg.hourBars(toDouble(r.netDropsByHour()), Svg.PURPLE, t("report.unitCount")));
        sb.append("</section>");
    }

    private void method() {
        sb.append("<section><h2>").append(esc(t("report.method"))).append("</h2><ul class='method'>");
        List<String> items = new ArrayList<>();
        if (r.hasLine()) items.add(t("report.m1", r.options().pollSeconds()));
        items.add(t("report.m2", r.options().pollSeconds()));
        if (r.hasLine()) items.add(t("report.m6"));
        items.add(t("report.m3", r.hasLine() ? pct(r.lineCoveragePct()) : t("common.dash"),
                r.hasNet() ? pct(r.netCoveragePct()) : t("common.dash")));
        items.add(t("report.m4"));
        if (r.hasLine()) items.add(t("report.m5"));
        for (String item : items) sb.append("<li>").append(esc(item)).append("</li>");
        sb.append("</ul></section>");
    }

    private void figure(String title, String legendHtml, String svg) {
        sb.append("<figure><figcaption><span>").append(esc(title)).append("</span>").append(legendHtml)
                .append("</figcaption>").append(svg).append("</figure>");
    }

    private String legend(String c1, String k1, String c2, String k2) {
        return "<span class='legend'><i style='background:" + c1 + "'></i>" + esc(t(k1))
                + "<i style='background:" + c2 + "'></i>" + esc(t(k2)) + "</span>";
    }

    // ---------- hesap yardimcilari ----------

    private static Outage longest(List<Outage> list) {
        return list.stream().filter(Outage::durationKnown).max(Comparator.comparingLong(Outage::durationSec)).orElse(null);
    }

    /** Suresi bilinen kesintilerin toplami; yaklasik sureler dahil (bulgularda ayrica belirtilir). */
    private static long knownTotal(List<Outage> list) {
        return list.stream().filter(Outage::durationKnown).mapToLong(Outage::durationSec).sum();
    }

    /** Yaklasik sureler "*" ile isaretlenir; aciklamasi tablonun altinda. */
    private static String durMark(Outage o) {
        return dur(o.durationSec()) + (o.approximate() ? " *" : "");
    }

    private static void addApproxNotes(List<String> items, List<Outage> list) {
        long approx = list.stream().filter(Outage::approximate).count();
        if (approx > 0) items.add(t("report.fApprox", approx));
        long unknown = list.stream().filter(o -> !o.durationKnown()).count();
        if (unknown > 0) items.add(t("report.fUnknownEnd", unknown));
    }

    private static int sum(int[] values) {
        int s = 0;
        for (int v : values) s += v;
        return s;
    }

    private static double[] toDouble(int[] values) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) out[i] = values[i];
        return out;
    }

    private static java.util.Optional<String> window(int[] byHour) {
        return window(toDouble(byHour), sum(byHour) >= MIN_EVENTS_FOR_WINDOW);
    }

    /**
     * Olaylarin yarisindan fazlasi ardisik {@value #WINDOW_HOURS} saatte toplaniyorsa
     * "%62 · 19:00–23:00" gibi bir ifade dondurur; gece yarisini asan pencereler dahil.
     */
    static java.util.Optional<String> window(double[] byHour, boolean enoughData) {
        if (!enoughData) return java.util.Optional.empty();
        double total = 0;
        for (double v : byHour) total += v;
        if (total <= 0) return java.util.Optional.empty();
        int bestStart = 0;
        double best = -1;
        for (int start = 0; start < 24; start++) {
            double s = 0;
            for (int k = 0; k < WINDOW_HOURS; k++) s += byHour[(start + k) % 24];
            // Esitlikte bos saatle baslamayan pencere secilir: "21-01" yerine "22-02"
            boolean tighter = s == best && byHour[start] > 0 && byHour[bestStart] == 0;
            if (s > best || tighter) {
                best = s;
                bestStart = start;
            }
        }
        double share = 100 * best / total;
        if (share <= 50) return java.util.Optional.empty();
        return java.util.Optional.of(String.format(Locale.ROOT, "%s · %02d:00–%02d:00",
                pct(share), bestStart, (bestStart + WINDOW_HOURS) % 24));
    }

    // ---------- bicimleme ----------

    /** Rapor metinleri her zaman bicimlenir; duz yuzde isareti paketlerde "%%" olarak yazilir. */
    private static String t(String key, Object... args) {
        return I18n.t(key, args);
    }

    static String num(double v, int decimals) {
        return String.format(I18n.locale(), "%,." + decimals + "f", v);
    }

    /**
     * Yuzde. 99-100 arasi iki basamakla yazilir: erisilebilirlikte %99,96 ile
     * %100 arasindaki fark ayda ~17 dakika kesinti demek, yuvarlanmamali.
     */
    static String pct(double v) {
        String s;
        if (v >= 99 && v < 100) s = num(Math.min(v, 99.99), 2);
        else s = num(v, v < 10 ? 1 : 0);
        // "5,0" -> "5", "99,50" -> "99,5"
        s = s.replaceAll("([.,]\\d*?)0+$", "$1").replaceAll("[.,]$", "");
        return I18n.t("fmt.percent", s);
    }

    private String dateTime(long ts) {
        return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", I18n.locale()).format(Instant.ofEpochMilli(ts).atZone(zone));
    }

    private String date(long ts) {
        return DateTimeFormatter.ofPattern("dd.MM.yyyy", I18n.locale()).format(Instant.ofEpochMilli(ts).atZone(zone));
    }

    /** Uzun sureler gun icerebilir: "2 gün 3 sa", "4 sa 12 dk", "45 sn". */
    static String dur(long seconds) {
        if (seconds <= 0) return I18n.t("fmt.durSeconds", 0);
        if (seconds < 60) return I18n.t("fmt.durSeconds", seconds);
        if (seconds < 3600) return I18n.t("fmt.durMinutesSeconds", seconds / 60, seconds % 60);
        if (seconds < 86_400) return I18n.t("fmt.durHoursMinutes", seconds / 3600, (seconds % 3600) / 60);
        return I18n.t("fmt.upDaysHours", seconds / 86_400, (seconds % 86_400) / 3600);
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static final String CSS = """
            :root{color-scheme:light;--text:#0f172a;--muted:#475569;--faint:#94a3b8;--line:#e2e8f0;--soft:#f8fafc}
            *{box-sizing:border-box}
            body{margin:0;background:#fff;color:var(--text);font:14px/1.5 "Segoe UI",system-ui,-apple-system,sans-serif}
            main{max-width:960px;margin:0 auto;padding:32px 24px 48px}
            h1{font-size:24px;margin:0}
            h2{font-size:17px;margin:32px 0 12px;padding-bottom:6px;border-bottom:1px solid var(--line)}
            .top{display:flex;justify-content:space-between;align-items:center;gap:16px;flex-wrap:wrap}
            button{font:inherit;padding:8px 14px;border-radius:8px;border:1px solid #2563eb;background:#2563eb;color:#fff;cursor:pointer}
            .meta{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:8px 24px;margin:16px 0 0}
            .meta dt{color:var(--muted);font-size:12px}.meta dd{margin:0;font-weight:600}
            .findings li,.method li{margin:4px 0}
            .method{color:var(--muted);font-size:13px}
            .stats{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px}
            .stat{border:1px solid var(--line);border-radius:10px;padding:12px 14px;background:var(--soft);break-inside:avoid}
            .stat .label{color:var(--muted);font-size:12px}.stat .value{font-size:22px;font-weight:600}
            .stat .sub{color:var(--muted);font-size:12px;min-height:18px}
            table{width:100%;border-collapse:collapse;font-size:12.5px}
            table.narrow{width:auto;min-width:320px}
            th{text-align:left;color:var(--muted);font-weight:600;border-bottom:1px solid var(--line);padding:6px 8px}
            td{border-bottom:1px solid var(--line);padding:6px 8px;vertical-align:top}
            tr{break-inside:avoid}.nowrap{white-space:nowrap}.num{text-align:right;font-variant-numeric:tabular-nums}
            .detail{color:var(--muted)}
            .kind{display:inline-block;padding:1px 8px;border-radius:999px;font-size:11.5px;font-weight:600}
            .kind.dsl{background:#fee2e2;color:#991b1b}.kind.net{background:#ede9fe;color:#5b21b6}
            .muted{color:var(--muted)}
            figure{margin:0 0 18px;break-inside:avoid}
            figcaption{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;font-weight:600;font-size:13px;margin-bottom:4px}
            .legend{font-weight:400;color:var(--muted);font-size:12px}
            .legend i{display:inline-block;width:10px;height:10px;border-radius:2px;margin:0 5px 0 12px;vertical-align:-1px}
            .chart{width:100%;height:auto;display:block;border:1px solid var(--line);border-radius:8px}
            .chart text{font-size:11px;fill:#64748b}.chart text.unit{fill:#94a3b8}.chart text.ref{font-size:10.5px}
            .chart .grid{stroke:#eef2f6}.chart .axis{stroke:#cbd5e1}
            footer{margin-top:36px;color:var(--faint);font-size:12px}
            @media (max-width:600px){main{padding:20px 16px}table{font-size:11.5px}.detail{display:none}}
            @media print{.noprint{display:none}main{padding:0;max-width:none}h2{break-after:avoid}@page{size:A4;margin:14mm}}
            """;
}
