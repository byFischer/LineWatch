package com.emre.linewatch.alert;

public interface Notifier {
    void notify(String title, String message, boolean urgent);
}
