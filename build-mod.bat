@echo off
REM =====================================================================
REM build-mod.bat - build-mod.sh の Windows 版
REM ---------------------------------------------------------------------
REM   全 Mod の推奨版をビルドする (ルートの buildRecommended タスク)。
REM
REM   JAVA_HOME はここでは設定しない。 設定する必要も無い。
REM   デーモン JVM は追跡ファイル gradle\gradle-daemon-jvm.properties が固定し、
REM   版ごとの toolchain (1.21.x=21 / 26.1+=25) は Gradle が解決する。
REM   手元に無いものは初回に自動ダウンロードされる (README 参照)。
REM
REM   使い方:
REM     build-mod.bat              -^> 推奨版 (MC 26.1.2) をビルド
REM     build-mod.bat build26_1    -^> ルートの任意のビルドタスクを渡す
REM =====================================================================
call "%~dp0gradlew.bat" buildRecommended %*
