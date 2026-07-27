package com.emre.linewatch.ui.widget;

import com.emre.linewatch.ui.Anim;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.DoubleFunction;

/**
 * Tek metrik kutusu: baslik, buyuk deger, degisim rozeti, trend cizgisi ve alt aciklama.
 * Sayisal degerler {@link #setValue(double, DoubleFunction)} ile verilirse
 * eski degerden yenisine sayarak degisir.
 */
public class MetricCard extends VBox {

    private final Label title = new Label();
    private final Label value = new Label("—");
    private final Label unit = new Label();
    private final Label delta = new Label();
    private final Label sub = new Label();
    private final Sparkline spark;

    private Timeline counter;
    private Double lastValue;

    public MetricCard(String titleText) {
        this(titleText, true, false);
    }

    /**
     * @param withSparkline trend cizgisi gosterilsin mi
     * @param bars          cizgi yerine cubuk (sayim metrikleri icin)
     */
    public MetricCard(String titleText, boolean withSparkline, boolean bars) {
        getStyleClass().add("card");
        setSpacing(7);

        title.setText(titleText);
        title.getStyleClass().add("card-title");
        value.getStyleClass().add("metric-value");
        unit.getStyleClass().add("metric-unit");
        delta.getStyleClass().add("metric-delta");
        delta.setVisible(false);
        delta.setManaged(false);
        sub.getStyleClass().add("metric-sub");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(8, title, spacer, delta);
        head.setAlignment(Pos.CENTER_LEFT);

        HBox valueRow = new HBox(6, value, unit);
        valueRow.setAlignment(Pos.BASELINE_LEFT);

        getChildren().addAll(head, valueRow);

        if (withSparkline) {
            spark = new Sparkline(bars);
            getChildren().add(spark);
        } else {
            spark = null;
        }
        getChildren().add(sub);
    }

    public Sparkline sparkline() {
        return spark;
    }

    /** Metin degeri dogrudan yazar (sayi olmayan metrikler icin). */
    public void setValue(String text) {
        stopCounter();
        lastValue = null;
        value.setText(text);
    }

    /** Sayisal degeri onceki degerden sayarak gunceller. */
    public void setValue(double v, DoubleFunction<String> format) {
        stopCounter();
        if (lastValue == null || Math.abs(v - lastValue) < 1e-9) {
            value.setText(format.apply(v));
        } else {
            counter = Anim.count(value, lastValue, v, format);
            counter.play();
        }
        lastValue = v;
    }

    public void setUnit(String text) {
        unit.setText(text == null ? "" : text);
    }

    public void setSub(String text) {
        sub.setText(text == null ? "" : text);
    }

    /** Deger rengi: "good", "warn", "bad" ya da null (notr). */
    public void setState(String state) {
        value.getStyleClass().removeAll("good", "warn", "bad");
        if (state != null) value.getStyleClass().add(state);
    }

    /**
     * Degisim rozeti. {@code kind}: "up" (yesil), "down" (kirmizi), "warn" (kehribar)
     * ya da null (notr). {@code text} null ise rozet gizlenir.
     */
    public void setDelta(String text, String kind) {
        boolean show = text != null && !text.isBlank();
        delta.setVisible(show);
        delta.setManaged(show);
        if (!show) return;
        delta.setText(text);
        delta.getStyleClass().removeAll("up", "down", "warn");
        if (kind != null) delta.getStyleClass().add(kind);
    }

    private void stopCounter() {
        if (counter != null) {
            counter.stop();
            counter = null;
        }
    }
}
