package com.emre.linewatch.ui.widget;

import com.emre.linewatch.ui.Theme;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/**
 * Canvas ile cizen bilesenlerin ortak tabani: canvas'i alanina sigdirir,
 * boyut degisiminde ve tema degisiminde yeniden cizdirir.
 *
 * <p>Tema dinleyicisi zayif (weak) tutuluyor: Olaylar ekranindaki detay grafigi
 * her secimde yeniden yaratildigi icin guclu referans birikimi olurdu.
 */
public abstract class CanvasPane extends Region {

    protected final Canvas canvas = new Canvas();
    private final ChangeListener<Theme.Palette> themeListener;

    protected CanvasPane() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);
        themeListener = (obs, old, now) -> redraw();
        Theme.paletteProperty().addListener(new WeakChangeListener<>(themeListener));
    }

    protected Theme.Palette palette() {
        return Theme.palette();
    }

    /** Rol adindan mevcut temanin rengini cozer. */
    protected static Color color(Theme.Palette p, Role role) {
        return switch (role) {
            case DOWN -> p.down();
            case UP -> p.up();
            case ACCENT -> p.accent();
            case GOOD -> p.good();
            case WARN -> p.warn();
            case BAD -> p.bad();
            case TEXT -> p.text();
            case MUTED -> p.textMuted();
            case FAINT -> p.textFaint();
        };
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
        }
        canvas.relocate(0, 0);
        redraw();
    }

    /** Veri degisince cagrilir; boyut henuz belli degilse sessizce gecer. */
    public void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        draw(g, w, h);
    }

    protected abstract void draw(GraphicsContext g, double w, double h);
}
