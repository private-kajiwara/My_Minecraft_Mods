@echo off
REM =====================================================================
REM build-mod.bat - build-mod.sh の Windows 版
REM ---------------------------------------------------------------------
REM   全 Mod の推奨版をビルドする (ルートの buildRecommended タスク)。
REM
REM   JAVA_HOME はここでは設定しない。 以前は特定マシンの JDK パスが
REM   直書きされており、 そのPCでしか動かなかった。 JDK の供給は Gradle 側に
REM   任せる (README の「必要なもの」/「toolchain」節を参照)。
REM
REM   使い方:
REM     build-mod.bat              -^> 推奨版 (MC 26.1.2) をビルド
REM     build-mod.bat build26_1    -^> ルートの任意のビルドタスクを渡す
REM =====================================================================
call "%~dp0gradlew.bat" buildRecommended %*
