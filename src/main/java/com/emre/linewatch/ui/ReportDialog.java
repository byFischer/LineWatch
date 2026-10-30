package com.emre.linewatch.ui;

import com.emre.linewatch.config.I18n;
import com.emre.linewatch.config.Settings;
import com.emre.linewatch.report.CsvExporter;
import com.emre.linewatch.report.HtmlReport;
import com.emre.linewatch.report.ReportBuilder;
import com.emre.linewatch.report.ReportData;
import com.emre.linewatch.report.ReportOptions;
import com.emre.linewatch.ui.widget.Segmented;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.awt.Desktop;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Kanit raporu penceresi: donem, abone bilgisi ve CSV secenegi alinir, rapor
 * arka planda uretilip tarayicida acilir.
 */
public final class ReportDialog {

    private static final int[] PRESET_DAYS = {7, 30, 90};
    /** Ayni anda tek rapor uretilsin; buyuk araliklar birkac saniye surebilir. */
    private static volatile boolean busy;

    private final Window owner;
    private final Settings settings;
    private final ReportBuilder builder;
    private final CsvExporter csv;
    private final String device;

    private final DatePicker fromPicker = new DatePicker();
    private final DatePicker toPicker = new DatePicker();
    private final TextField subscriber = new TextField();
    private final TextField plan = new TextField();
    private final CheckBox includeCsv = new CheckBox(I18n.t("reportDlg.csv"));
    private final Label error = Ui.faint("");

    private ReportDialog(Window owner, Settings settings, ReportBuilder builder, CsvExporter csv, String device) {
        this.owner = owner;
        this.settings = settings;
        this.builder = builder;
        this.csv = csv;
        this.device = device;
    }

    /** @param device rapordaki cihaz adi; modem izlenmiyorsa bos */
    public static void show(Window owner, Settings settings, ReportBuilder builder, CsvExporter csv, String device) {
        if (busy) return;
        new ReportDialog(owner, settings, builder, csv, device).open();
    }

    private void open() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.t("reportDlg.title"));
        dialog.setHeaderText(I18n.t("reportDlg.header"));

        var pane = dialog.getDialogPane();
        pane.getStylesheets().add(Theme.class.getResource("/com/emre/linewatch/styles.css").toExternalForm());
        if (!Theme.isDark()) pane.getStyleClass().add("theme-light");

        ButtonType create = new ButtonType(I18n.t("reportDlg.create"), ButtonBar.ButtonData.OK_DONE);
        // ButtonType.CANCEL JavaFX'in kendi dilinde yazilir; secili dille uyumlu olsun diye ozel tip
        ButtonType cancel = new ButtonType(I18n.t("reportDlg.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().addAll(create, cancel);
        pane.setContent(buildForm());
        pane.setPrefWidth(460);

        Button createButton = (Button) pane.lookupButton(create);
        createButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        createButton.addEventFilter(ActionEvent.ACTION, e -> {
            if (!validate()) e.consume();
        });

        dialog.showAndWait().filter(b -> b == create).ifPresent(b -> generate());
    }

    private Node buildForm() {
        LocalDate today = LocalDate.now();
        fromPicker.setValue(today.minusDays(30));
        toPicker.setValue(today);
        for (DatePicker p : new DatePicker[]{fromPicker, toPicker}) {
            p.setEditable(false);
            p.setMaxWidth(Double.MAX_VALUE);
            p.setDayCellFactory(dp -> new javafx.scene.control.DateCell() {
                @Override
                public void updateItem(LocalDate item, boolean empty) {
                    super.updateItem(item, empty);
                    setDisable(empty || item.isAfter(LocalDate.now()));
                }
            });
        }

        String[] labels = new String[PRESET_DAYS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = I18n.t("reportDlg.lastDays", PRESET_DAYS[i]);
        Segmented presets = new Segmented(labels);
        presets.select(1);
        presets.setOnSelect(i -> {
            toPicker.setValue(LocalDate.now());
            fromPicker.setValue(LocalDate.now().minusDays(PRESET_DAYS[i]));
        });

        subscriber.setText(settings.reportSubscriber());
        subscriber.setPromptText(I18n.t("reportDlg.subscriberPrompt"));
        double planMbps = settings.reportPlanMbps();
        plan.setText(planMbps > 0 ? plainNumber(planMbps) : "");
        plan.setPromptText(I18n.t("reportDlg.planPrompt"));
        includeCsv.setSelected(false);

        VBox fromCol = Ui.col(4, Ui.faint(I18n.t("reportDlg.from")), fromPicker);
        VBox toCol = Ui.col(4, Ui.faint(I18n.t("reportDlg.to")), toPicker);
        javafx.scene.layout.HBox.setHgrow(fromCol, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.layout.HBox.setHgrow(toCol, javafx.scene.layout.Priority.ALWAYS);

        VBox form = new VBox(8,
                Ui.cardTitle(I18n.t("reportDlg.period")), presets,
                Ui.row(8, fromCol, toCol),
                Ui.cardTitle(I18n.t("reportDlg.subscriber")), subscriber,
                Ui.cardTitle(I18n.t("reportDlg.plan")), plan,
                Ui.faint(I18n.t("reportDlg.planHint")),
                includeCsv, error);
        form.setFillWidth(true);
        VBox.setMargin(includeCsv, new javafx.geometry.Insets(6, 0, 0, 0));
        return form;
    }

    private boolean validate() {
        LocalDate from = fromPicker.getValue();
        LocalDate to = toPicker.getValue();
        if (from == null || to == null || from.isAfter(to)) {
            error.setText(I18n.t("reportDlg.badRange"));
            return false;
        }
        if (parsePlan() < 0) {
            error.setText(I18n.t("reportDlg.badPlan"));
            plan.getStyleClass().add("invalid");
            return false;
        }
        return true;
    }

    /** Bos -> 0, gecersiz -> -1. Turkce klavyede virgul de ondalik ayrac kabul edilir. */
    private double parsePlan() {
        String text = plan.getText() == null ? "" : plan.getText().strip().replace(',', '.');
        if (text.isEmpty()) return 0;
        try {
            double v = Double.parseDouble(text);
            return v >= 0 && v < 100_000 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void generate() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate fromDate = fromPicker.getValue();
        LocalDate toDate = toPicker.getValue();
        long from = fromDate.atStartOfDay(zone).toInstant().toEpochMilli();
        long to = toDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;
        double planMbps = parsePlan();

        settings.reportSubscriber(subscriber.getText() == null ? "" : subscriber.getText().strip());
        settings.reportPlanMbps(planMbps);
        try {
            settings.save();
        } catch (Exception ignored) {
            // saklanamamasi raporu engellemez; bir dahaki sefere alanlar bos gelir
        }

        DateTimeFormatter f = DateTimeFormatter.ISO_LOCAL_DATE;
        String baseName = "LineWatch-" + I18n.t("reportDlg.filePrefix") + "-" + f.format(fromDate) + "_" + f.format(toDate);
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("reportDlg.saveTitle"));
        chooser.setInitialFileName(baseName + ".html");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("HTML", "*.html"));
        File documents = new File(System.getProperty("user.home"), "Documents");
        if (documents.isDirectory()) chooser.setInitialDirectory(documents);
        File target = chooser.showSaveDialog(owner);
        if (target == null) return;

        ReportOptions options = new ReportOptions(settings.reportSubscriber(), planMbps, device,
                settings.pollSeconds(), settings.snrThresholdDb());
        boolean withCsv = includeCsv.isSelected();
        busy = true;
        Thread worker = new Thread(() -> {
            try {
                ReportData data = builder.build(from, to, options);
                Path html = target.toPath();
                Files.writeString(html, HtmlReport.render(data), StandardCharsets.UTF_8);
                if (withCsv) {
                    String prefix = html.getFileName().toString().replaceFirst("(?i)\\.html?$", "");
                    csv.export(from, to, html.toAbsolutePath().getParent(), prefix);
                }
                openInBrowser(html);
            } catch (Exception e) {
                Platform.runLater(() -> showError(e));
            } finally {
                busy = false;
            }
        }, "report-builder");
        worker.setDaemon(true);
        worker.start();
    }

    private void openInBrowser(Path html) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(html.toUri());
                return;
            }
        } catch (Exception ignored) {
            // tarayici acilamadiysa en azindan dosyanin yerini soyle
        }
        Platform.runLater(() -> {
            Alert info = new Alert(Alert.AlertType.INFORMATION, I18n.t("reportDlg.saved", html));
            info.initOwner(owner);
            info.show();
        });
    }

    private void showError(Exception e) {
        Alert alert = new Alert(Alert.AlertType.ERROR, I18n.t("reportDlg.failed", String.valueOf(e.getMessage())));
        alert.initOwner(owner);
        alert.show();
    }

    /** Plan hizini alana yazarken gereksiz ".0" gostermemek icin. */
    private static String plainNumber(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
