@echo off
rem LineWatch'i Java kurulumu gerektirmeyen tek klasorluk uygulamaya cevirir.
rem Cikti: dist\LineWatch\LineWatch.exe
cd /d "%~dp0"
echo [1/2] Maven paketi derleniyor...
call mvn -q package -DskipTests || exit /b 1
echo [2/2] jpackage app-image olusturuluyor...
if exist dist rmdir /s /q dist
jpackage --type app-image ^
  --name LineWatch ^
  --input target ^
  --main-jar linewatch-0.1.0-jar-with-dependencies.jar ^
  --main-class com.emre.linewatch.Launcher ^
  --icon linewatch.ico ^
  --dest dist ^
  --java-options "-Xms32m" ^
  --java-options "-Xmx192m" ^
  --java-options "-XX:+UseSerialGC" ^
  --java-options "-XX:MinHeapFreeRatio=10" ^
  --java-options "-XX:MaxHeapFreeRatio=30" ^
  --app-version 0.1.0 ^
  --vendor "Emre" || exit /b 1
echo.
echo Tamam: dist\LineWatch\LineWatch.exe
