@echo off
rem LineWatch'i konsol penceresi olmadan, dusuk bellek limitiyle baslatir
start "" javaw -Xms32m -Xmx192m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -jar "%~dp0target\linewatch-0.1.0-jar-with-dependencies.jar"
