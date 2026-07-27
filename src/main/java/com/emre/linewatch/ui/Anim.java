package com.emre.linewatch.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.function.DoubleFunction;

/** Kucuk, abartisiz hareketler: ekran gecisi, sayi sayma, nabiz, vurgu. */
public final class Anim {

    private Anim() {
    }

    /**
     * Icerik alanindaki ekrani capraz gecisle degistirir: eskisi soner,
     * yenisi hafifce yukari kayarak belirir.
     */
    public static void swap(StackPane host, Node next) {
        if (host.getChildren().isEmpty()) {
            host.getChildren().add(next);
            return;
        }
        Node old = host.getChildren().get(host.getChildren().size() - 1);
        if (old == next) return;

        next.setOpacity(0);
        next.setTranslateY(7);
        if (!host.getChildren().contains(next)) host.getChildren().add(next);

        FadeTransition fadeOut = new FadeTransition(Duration.millis(110), old);
        fadeOut.setToValue(0);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(180), next);
        fadeIn.setToValue(1);
        TranslateTransition rise = new TranslateTransition(Duration.millis(180), next);
        rise.setToY(0);
        rise.setInterpolator(Interpolator.EASE_OUT);

        ParallelTransition all = new ParallelTransition(fadeOut,
                new SequentialTransition(new PauseTransition(Duration.millis(55)),
                        new ParallelTransition(fadeIn, rise)));
        all.setOnFinished(e -> {
            // hizli tiklamalarda arti kalan eski ekranlari da temizle
            host.getChildren().removeIf(n -> n != next);
            old.setOpacity(1);
            old.setTranslateY(0);
            next.setOpacity(1);
            next.setTranslateY(0);
        });
        all.play();
    }

    /**
     * Etiketteki sayiyi {@code from}'dan {@code to}'ya sayar.
     * Timeline dondurulur ki cagiran onceki animasyonu durdurabilsin.
     */
    public static Timeline count(Label label, double from, double to, DoubleFunction<String> format) {
        DoubleProperty value = new SimpleDoubleProperty(from);
        value.addListener((obs, old, now) -> label.setText(format.apply(now.doubleValue())));
        Timeline tl = new Timeline(new KeyFrame(Duration.millis(420),
                new KeyValue(value, to, Interpolator.EASE_OUT)));
        tl.setOnFinished(e -> label.setText(format.apply(to)));
        return tl;
    }

    /** Canli durum noktasi icin surekli nabiz. */
    public static Timeline pulse(Node node) {
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(node.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(950),
                        new KeyValue(node.opacityProperty(), 0.3, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(1900),
                        new KeyValue(node.opacityProperty(), 1.0, Interpolator.EASE_BOTH)));
        tl.setCycleCount(Animation.INDEFINITE);
        return tl;
    }

    /** Yeni gelen ogeyi kisaca vurgular. */
    public static void highlight(Node node) {
        FadeTransition fade = new FadeTransition(Duration.millis(420), node);
        fade.setFromValue(0.2);
        fade.setToValue(1);
        fade.play();
    }

    /** Deger degisiminde kucuk bir olcek vurgusu. */
    public static void pop(Node node) {
        ScaleTransition scale = new ScaleTransition(Duration.millis(150), node);
        scale.setFromX(0.95);
        scale.setFromY(0.95);
        scale.setToX(1);
        scale.setToY(1);
        scale.setInterpolator(Interpolator.EASE_OUT);
        scale.play();
    }
}
