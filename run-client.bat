@echo off
REM =====================================================================
REM run-client.bat — OmniChest (Stonecutter) クライアント起動ヘルパ
REM ---------------------------------------------------------------------
REM   omnichest は自己完結した Stonecutter included build。 版ノードタスク
REM   :<MC>:runClient を mods\omnichest の中で実行する。
REM
REM   JAVA_HOME はここでは設定しない。 以前は特定マシンの JDK パスが
REM   直書きされており、 そのPCでしか動かなかった。 JDK の供給は Gradle 側に
REM   任せる (README の「必要なもの」/「toolchain」節を参照)。
REM
REM   使い方:
REM     run-client.bat              -^> 推奨版 26.1.2 を起動
REM     run-client.bat 1.21.11      -^> 指定した MC 版を起動
REM =====================================================================
setlocal
set "MC=%~1"
if "%MC%"=="" set "MC=26.1.2"

pushd "%~dp0mods\omnichest"
call gradlew.bat :%MC%:runClient
set "RC=%ERRORLEVEL%"
popd
endlocal & exit /b %RC%
