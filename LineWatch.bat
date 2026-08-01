@echo off
rem LineWatch'i konsol penceresi olmadan, dusuk bellek limitiyle baslatir.
rem Jar adi surumu icerdigi icin sabit yazilmaz, klasorden bulunur.
setlocal
set "JAR="
for %%f in ("%~dp0target\*-jar-with-dependencies.jar") do set "JAR=%%~ff"
if not defined JAR (
  echo Dagitim jar'i bulunamadi. Once "mvn package" calistir.
  pause
  exit /b 1
)
start "" javaw -Xms32m -Xmx192m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -jar "%JAR%"
