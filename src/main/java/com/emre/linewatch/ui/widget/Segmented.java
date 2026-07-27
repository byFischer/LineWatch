package com.emre.linewatch.ui.widget;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** ComboBox yerine kullanilan segment secici: tum secenekler tek bakista gorunur. */
public class Segmented extends HBox {

    private final List<Label> segments = new ArrayList<>();
    private int selected;
    private IntConsumer onSelect;

    public Segmented(String... labels) {
        getStyleClass().add("segmented");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(2);
        for (int i = 0; i < labels.length; i++) {
            Label seg = new Label(labels[i]);
            seg.getStyleClass().add("segment");
            int index = i;
            seg.setOnMouseClicked(e -> select(index));
            segments.add(seg);
            getChildren().add(seg);
        }
        refresh();
    }

    public void setOnSelect(IntConsumer listener) {
        this.onSelect = listener;
    }

    public int selected() {
        return selected;
    }

    public void select(int index) {
        if (index < 0 || index >= segments.size() || index == selected) return;
        selected = index;
        refresh();
        if (onSelect != null) onSelect.accept(index);
    }

    private void refresh() {
        for (int i = 0; i < segments.size(); i++) {
            Label seg = segments.get(i);
            seg.getStyleClass().remove("active");
            if (i == selected) seg.getStyleClass().add("active");
        }
    }
}
