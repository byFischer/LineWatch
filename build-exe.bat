@echo off
rem LineWatch'i Java kurulumu gerektirmeyen tek klasorluk uygulamaya cevirir.
rem Cikti: dist\LineWatch\LineWatch.exe
rem (Ayni isi tag push'unda GitHub Actions da yapiyor: .github\workflows\release.yml)
setlocal
cd /d "%~dp0"

echo [1/3] Maven paketi derleniyor...
call mvn -q package -DskipTests || exit /b 1

echo [2/3] Dagitim jar'i hazirlaniyor...
rem Jar adi surumu icerdigi icin sabit yazilmaz, klasorden bulunur.
set "JAR="
for %%f in (target\*-jar-with-dependencies.jar) do set "JAR=%%~nxf"
if not defined JAR (
  echo HATA: target\ altinda dagitim jar'i bulunamadi.
  exit /b 1
)
rem linewatch-0.1.0-jar-with-dependencies.jar -^> 0.1.0
for /f "tokens=2 delims=-" %%v in ("%JAR%") do set "VER=%%v"
if not defined VER set "VER=0.1.0"
echo       %JAR% ^(surum %VER%^)

rem Sadece calistirilabilir jar imaja girsin; "target" klasorunun tamami
rem verilirse classes\, test-classes\, surefire-reports\ de kopyalanip imaj sisiyor.
if exist staging rmdir /s /q staging
mkdir staging
copy /y "target\%JAR%" staging\ >nul

echo [3/3] jpackage app-image olusturuluyor...
if exist dist rmdir /s /q dist
jpackage --type app-image ^
  --name LineWatch ^
  --input staging ^
  --main-jar "%JAR%" ^
  --main-class com.emre.linewatch.Launcher ^
  --icon linewatch.ico ^
  --dest dist ^
  --app-version %VER% ^
  --vendor "Emre Zeytin" ^
  --copyright "Copyright (c) 2026 Emre Zeytin" ^
  --description "DSL hat izleme ve bildirim uygulamasi" ^
  --java-options "-Xms16m" ^
  --java-options "-Xmx160m" ^
  --java-options "-XX:+UseSerialGC" ^
  --java-options "-XX:MinHeapFreeRatio=10" ^
  --java-options "-XX:MaxHeapFreeRatio=20" ^
  --java-options "-XX:TieredStopAtLevel=1" ^
  --java-options "-XX:ReservedCodeCacheSize=32m" ^
  --java-options "-XX:+UseCompactObjectHeaders" ^
  --java-options "-Dsun.java2d.d3d=false" ^
  --java-options "-Dsun.java2d.noddraw=true" ^
  --java-options "--enable-native-access=ALL-UNNAMED" || exit /b 1
|| exit /b 1

rmdir /s /q staging
echo.
echo Tamam: dist\LineWatch\LineWatch.exe
