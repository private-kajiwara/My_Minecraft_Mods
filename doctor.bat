@echo off
REM =====================================================================
REM doctor.bat - doctor.sh の Windows 版
REM ---------------------------------------------------------------------
REM   clone 直後に叩ける前提チェック。 Gradle を起動しないので Gradle が
REM   まだ動かない状態でも実行できる。 既存のビルドロジックには割り込まない。
REM
REM   使い方: doctor.bat
REM   終了コード: 0 = FAIL 無し / 1 = FAIL あり
REM =====================================================================
setlocal EnableDelayedExpansion
pushd "%~dp0"
set "FAILED=0"

echo === 1. Git ===
where git >nul 2>&1
if errorlevel 1 (
  echo   [FAIL]  git が見つかりません
  echo      -^> https://git-scm.com/downloads から導入してください
  set "FAILED=1"
) else (
  for /f "tokens=3" %%v in ('git --version') do echo   [ OK ]  git %%v
)

echo === 2. Java ^(Gradle ランチャ用^) ===
REM デーモンの JVM は gradle\gradle-daemon-jvm.properties が決め、 無ければ
REM Gradle が自動ダウンロードする。 ここで要るのはランチャ用の java だけ。
set "JAVA_BIN="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_BIN (
  for /f "delims=" %%j in ('where java 2^>nul') do if not defined JAVA_BIN set "JAVA_BIN=%%j"
)
if not defined JAVA_BIN (
  echo   [FAIL]  java が見つかりません ^(JAVA_HOME も PATH も不可^)
  echo      -^> JDK 17 以上を入れて JAVA_HOME を設定するか PATH に通してください
  set "FAILED=1"
) else (
  REM JAVA_BIN にスペースを含むパス ("C:\Program Files\...") が入るため、
  REM for /f の中で直接呼ぶと cmd がコマンド行を誤解する。 一度ファイルに出す。
  "%JAVA_BIN%" -version 2>"%TEMP%\mmm_doctor_java.txt"
  for /f "tokens=3" %%v in ('findstr /i "version" "%TEMP%\mmm_doctor_java.txt"') do (
    if not defined JV set "JV=%%~v"
  )
  del "%TEMP%\mmm_doctor_java.txt" >nul 2>&1
  if defined JV (
    echo   [ OK ]  java !JV!  ^(17 未満なら JDK 17 以上を推奨^)
  ) else (
    echo   [WARN]  java の版を判定できませんでした ^(%JAVA_BIN%^)
  )
)

echo === 3. リポジトリが供給するもの ===
if exist gradlew.bat (echo   [ OK ]  gradlew.bat あり) else (echo   [FAIL]  gradlew.bat がありません & set "FAILED=1")
if exist gradle\wrapper\gradle-wrapper.jar (echo   [ OK ]  gradle-wrapper.jar あり) else (echo   [FAIL]  gradle-wrapper.jar がありません & set "FAILED=1")
if exist gradle\gradle-daemon-jvm.properties (
  for /f "tokens=2 delims==" %%d in ('findstr /b "toolchainVersion" gradle\gradle-daemon-jvm.properties') do set "DJV=%%d"
  echo   [ OK ]  gradle-daemon-jvm.properties あり ^(デーモン JVM: !DJV!^)
) else (
  echo   [WARN]  gradle\gradle-daemon-jvm.properties がありません
  echo      -^> デーモン JVM がマシン依存になります
)

echo === 4. パス長 ===
set "ROOT=%CD%"
call :strlen ROOT ROOTLEN
REM ビルド生成物の最深相対パスは実測 243 文字 (loom-cache の
REM minecraft-clientOnly-<hash>-<MC>-loom.mappings...jar.backup)。
REM 従来の MAX_PATH は 260 なので、 ルートに使える余裕は 260-243-1 = 16 文字。
REM 実質どこに clone しても足りず、 Windows では長パス有効化が事実上の前提。
for /f "tokens=3" %%r in ('reg query "HKLM\SYSTEM\CurrentControlSet\Control\FileSystem" /v LongPathsEnabled 2^>nul ^| findstr /i LongPathsEnabled') do set "LPE=%%r"
if "!LPE!"=="0x1" (
  echo   [ OK ]  Windows LongPathsEnabled=1 ^(パス長 !ROOTLEN! 文字のルートでも可^)
) else (
  echo   [FAIL]  Windows の長パスが無効 ^(LongPathsEnabled=!LPE!^) / ルートは !ROOTLEN! 文字
  echo      -^> 生成物の最深相対パスが 243 文字あり 260 制限に収まりません。 管理者権限で:
  echo         reg add "HKLM\SYSTEM\CurrentControlSet\Control\FileSystem" /v LongPathsEnabled /t REG_DWORD /d 1 /f
  set "FAILED=1"
)
for /f "delims=" %%p in ('git config --get core.longpaths 2^>nul') do set "LP=%%p"
if /i "!LP!"=="true" (
  echo   [ OK ]  git core.longpaths=true
) else (
  echo   [WARN]  git core.longpaths が true ではありません
  echo      -^> git config --global core.longpaths true ^(長いパスの checkout に必要^)
)

echo === 5. ディスク空き ===
for /f "tokens=3" %%s in ('dir /-c "%CD%" ^| findstr /c:"bytes free"') do set "FREEB=%%s"
if defined FREEB (
  set /a "FREEGB=!FREEB:~0,-9!"
  echo   [ OK ]  空き 約 !FREEGB! GB  ^(初回ビルドで 5-10 GB 使います^)
) else (
  echo   [WARN]  空き容量を確認できませんでした
)

echo === 6. ネットワーク到達性 ^(初回ビルドに必須^) ===
where curl >nul 2>&1
if errorlevel 1 (
  echo   [WARN]  curl が無く到達性を確認できません
) else (
  set "UNREACHABLE="
  for %%h in (services.gradle.org plugins.gradle.org repo.maven.apache.org maven.fabricmc.net meta.fabricmc.net launchermeta.mojang.com libraries.minecraft.net maven.kikugie.dev maven.terraformersmc.com maven.shedaniel.me api.modrinth.com api.foojay.io) do (
    curl -sS -m 12 -o NUL "https://%%h" >nul 2>&1
    if errorlevel 1 set "UNREACHABLE=!UNREACHABLE! %%h"
  )
  if defined UNREACHABLE (
    echo   [WARN]  到達できないホスト:!UNREACHABLE!
    echo      -^> プロキシ等で遮断されている可能性。 初回ビルドが失敗します
  ) else (
    echo   [ OK ]  必須ホスト全て到達可
  )
)

echo.
if "!FAILED!"=="0" (
  echo FAIL はありません。 .\gradlew.bat buildRecommended を実行できます。
  echo ^(初回はおよそ 1 GB のダウンロードと 10 分前後がかかります^)
) else (
  echo FAIL があります。 上の -^> の指示を解消してください。
)
popd
endlocal & exit /b %FAILED%

:strlen
setlocal EnableDelayedExpansion
set "s=!%~1!#"
set "len=0"
for %%A in (4096 2048 1024 512 256 128 64 32 16 8 4 2 1) do (
  if "!s:~%%A!" NEQ "" (
    set /a "len+=%%A"
    set "s=!s:~%%A!"
  )
)
endlocal & set "%~2=%len%"
goto :eof
