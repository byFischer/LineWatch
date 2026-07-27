package com.emre.linewatch.ui;

import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Cercevesiz (undecorated) pencerenin kendi kabugu: surukleme, kenardan
 * boyutlandirma ve tam ekran.
 *
 * <p>Tam ekran icin {@code stage.setMaximized} yerine ekranin gorunur alani
 * elle ayarlaniyor; bazi JavaFX surumlerinde cercevesiz pencere maximize
 * edilince gorev cubugunun uzerini kapatiyor.
 */
public class WindowChrome {

    private static final double EDGE = 6;

    private final Stage stage;
    private final Region root;

    private boolean maximized;
    private Rectangle2D restoreBounds;
    private Runnable onMaximizedChanged;

    private Cursor edgeCursor = Cursor.DEFAULT;
    private boolean resizing;
    private double pressScreenX;
    private double pressScreenY;
    private double pressX;
    private double pressY;
    private double pressW;
    private double pressH;

    public WindowChrome(Stage stage, Region root) {
        this.stage = stage;
        this.root = root;
        installResize();
    }

    public boolean isMaximized() {
        return maximized;
    }

    public void setOnMaximizedChanged(Runnable listener) {
        this.onMaximizedChanged = listener;
    }

    /** Verilen dugumu surukleme alani yapar; cift tiklama tam ekrana gecirir. */
    public void makeDraggable(Node handle) {
        final double[] offset = new double[2];
        handle.setOnMousePressed(e -> {
            offset[0] = e.getScreenX() - stage.getX();
            offset[1] = e.getScreenY() - stage.getY();
        });
        handle.setOnMouseDragged(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (maximized) {
                // tam ekrandan asagi cekilince pencereyi imlecin altinda geri ac
                double ratio = restoreBounds == null ? 0.5
                        : (e.getScreenX() - stage.getX()) / Math.max(1, stage.getWidth());
                toggleMaximize();
                offset[0] = stage.getWidth() * ratio;
                offset[1] = e.getScreenY() - stage.getY();
            }
            stage.setX(e.getScreenX() - offset[0]);
            stage.setY(e.getScreenY() - offset[1]);
        });
        handle.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) toggleMaximize();
        });
    }

    public void toggleMaximize() {
        if (maximized) {
            if (restoreBounds != null) {
                stage.setX(restoreBounds.getMinX());
                stage.setY(restoreBounds.getMinY());
                stage.setWidth(restoreBounds.getWidth());
                stage.setHeight(restoreBounds.getHeight());
            }
            maximized = false;
        } else {
            restoreBounds = new Rectangle2D(stage.getX(), stage.getY(),
                    stage.getWidth(), stage.getHeight());
            Rectangle2D visible = screenOf().getVisualBounds();
            stage.setX(visible.getMinX());
            stage.setY(visible.getMinY());
            stage.setWidth(visible.getWidth());
            stage.setHeight(visible.getHeight());
            maximized = true;
        }
        root.getStyleClass().remove("maximized");
        if (maximized) root.getStyleClass().add("maximized");
        if (onMaximizedChanged != null) onMaximizedChanged.run();
    }

    private Screen screenOf() {
        return Screen.getScreensForRectangle(stage.getX(), stage.getY(),
                        Math.max(1, stage.getWidth()), Math.max(1, stage.getHeight()))
                .stream().findFirst().orElse(Screen.getPrimary());
    }

    // ---------- kenardan boyutlandirma ----------

    private void installResize() {
        root.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (maximized) {
                edgeCursor = Cursor.DEFAULT;
                root.setCursor(Cursor.DEFAULT);
                return;
            }
            edgeCursor = cursorFor(e.getX(), e.getY());
            root.setCursor(edgeCursor);
        });

        root.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (maximized || edgeCursor == Cursor.DEFAULT) return;
            resizing = true;
            pressScreenX = e.getScreenX();
            pressScreenY = e.getScreenY();
            pressX = stage.getX();
            pressY = stage.getY();
            pressW = stage.getWidth();
            pressH = stage.getHeight();
            e.consume();
        });

        root.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!resizing) return;
            double dx = e.getScreenX() - pressScreenX;
            double dy = e.getScreenY() - pressScreenY;
            double minW = Math.max(400, stage.getMinWidth());
            double minH = Math.max(300, stage.getMinHeight());

            if (edgeCursor == Cursor.E_RESIZE || edgeCursor == Cursor.NE_RESIZE
                    || edgeCursor == Cursor.SE_RESIZE) {
                stage.setWidth(Math.max(minW, pressW + dx));
            }
            if (edgeCursor == Cursor.W_RESIZE || edgeCursor == Cursor.NW_RESIZE
                    || edgeCursor == Cursor.SW_RESIZE) {
                double width = Math.max(minW, pressW - dx);
                stage.setX(pressX + (pressW - width));
                stage.setWidth(width);
            }
            if (edgeCursor == Cursor.S_RESIZE || edgeCursor == Cursor.SE_RESIZE
                    || edgeCursor == Cursor.SW_RESIZE) {
                stage.setHeight(Math.max(minH, pressH + dy));
            }
            if (edgeCursor == Cursor.N_RESIZE || edgeCursor == Cursor.NE_RESIZE
                    || edgeCursor == Cursor.NW_RESIZE) {
                double height = Math.max(minH, pressH - dy);
                stage.setY(pressY + (pressH - height));
                stage.setHeight(height);
            }
            e.consume();
        });

        root.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> resizing = false);
        root.addEventFilter(MouseEvent.MOUSE_EXITED, e -> {
            if (!resizing) root.setCursor(Cursor.DEFAULT);
        });
    }

    private Cursor cursorFor(double x, double y) {
        double w = root.getWidth();
        double h = root.getHeight();
        boolean left = x <= EDGE;
        boolean right = x >= w - EDGE;
        boolean top = y <= EDGE;
        boolean bottom = y >= h - EDGE;

        if (top && left) return Cursor.NW_RESIZE;
        if (top && right) return Cursor.NE_RESIZE;
        if (bottom && left) return Cursor.SW_RESIZE;
        if (bottom && right) return Cursor.SE_RESIZE;
        if (left) return Cursor.W_RESIZE;
        if (right) return Cursor.E_RESIZE;
        if (top) return Cursor.N_RESIZE;
        if (bottom) return Cursor.S_RESIZE;
        return Cursor.DEFAULT;
    }
}
