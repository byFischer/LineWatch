package com.emre.linewatch.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/** Ekranlarda tekrar eden kucuk dugum kaliplari. */
public final class Ui {

    private Ui() {
    }

    // ---------- kaplar ----------

    public static VBox card(Node... children) {
        return card(12, children);
    }

    public static VBox card(double spacing, Node... children) {
        VBox box = new VBox(spacing, children);
        box.getStyleClass().add("card");
        return box;
    }

    public static VBox hero(double spacing, Node... children) {
        VBox box = new VBox(spacing, children);
        box.getStyleClass().add("card-hero");
        return box;
    }

    public static HBox row(double spacing, Node... children) {
        HBox box = new HBox(spacing, children);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public static VBox col(double spacing, Node... children) {
        return new VBox(spacing, children);
    }

    // ---------- metin ----------

    public static Label eyebrow(String text) {
        return styled(new Label(text), "eyebrow");
    }

    public static Label cardTitle(String text) {
        return styled(new Label(text), "card-title");
    }

    public static Label h1(String text)    { return styled(new Label(text), "h1"); }
    public static Label h2(String text)    { return styled(new Label(text), "h2"); }
    public static Label body(String text)  { return styled(new Label(text), "body"); }
    public static Label muted(String text) { return styled(new Label(text), "muted"); }
    public static Label faint(String text) { return styled(new Label(text), "faint"); }
    public static Label mono(String text)  { return styled(new Label(text), "mono"); }

    private static Label styled(Label label, String styleClass) {
        label.getStyleClass().add(styleClass);
        return label;
    }

    // ---------- rozetler ----------

    /** kind: "up", "down", "warn" ya da null (notr). */
    public static Label chip(String text, String kind) {
        Label label = new Label(text);
        label.getStyleClass().add("status-chip");
        if (kind != null) label.getStyleClass().add(kind);
        return label;
    }

    /** kind: "up", "down", "warn", "unknown". */
    public static Circle dot(double radius, String kind) {
        Circle circle = new Circle(radius);
        circle.getStyleClass().addAll("status-dot", kind);
        return circle;
    }

    public static void setDotKind(Circle dot, String kind) {
        dot.getStyleClass().removeAll("up", "down", "warn", "unknown");
        dot.getStyleClass().add(kind);
    }

    // ---------- butonlar ----------

    public static Button button(String text, Node icon, String... extraClasses) {
        Button button = new Button(text);
        if (icon != null) {
            button.setGraphic(icon);
            button.setGraphicTextGap(7);
        }
        button.getStyleClass().addAll(extraClasses);
        return button;
    }

    public static Button iconButton(Node icon, String tooltip, String... extraClasses) {
        Button button = new Button();
        button.setGraphic(icon);
        button.getStyleClass().addAll(extraClasses);
        if (tooltip != null) install(button, tooltip);
        return button;
    }

    public static void install(Node node, String text) {
        Tooltip tip = new Tooltip(text);
        tip.setShowDelay(Duration.millis(350));
        Tooltip.install(node, tip);
    }

    // ---------- bosluk ----------

    public static Region hSpacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    public static Region vSpacer() {
        Region region = new Region();
        VBox.setVgrow(region, Priority.ALWAYS);
        return region;
    }

    /** Bos durum blogu: baslik + aciklama, ortalanmis. */
    public static VBox emptyState(String title, String detail) {
        Label t = styled(new Label(title), "empty-title");
        Label d = styled(new Label(detail), "empty-detail");
        VBox box = new VBox(5, t, d);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new javafx.geometry.Insets(28, 12, 28, 12));
        return box;
    }

    /**
     * Sayfalarin arka plan yukleyicisi: tek thread, siradaki isler sirayla.
     * Bos kalan thread 30 sn sonra kapanir (tepsideyken bosuna yasamasin);
     * sayfa atildiktan sonra gelen isler sessizce dusurulur.
     */
    public static java.util.concurrent.ExecutorService loader(String name) {
        var executor = new java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, name);
                    t.setDaemon(true);
                    return t;
                }, new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
