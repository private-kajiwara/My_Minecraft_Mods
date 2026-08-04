#!/usr/bin/env sh
# =====================================================================
# doctor.sh — clone 直後に叩ける前提チェック (macOS / Linux / Git Bash)
# ---------------------------------------------------------------------
#   「pull しただけで開発できる」ために、 リポジトリ側で用意できないもの
#   (Git / JVM / ネットワーク / ディスク / Windows の長パス設定) だけを
#   検査し、 足りなければ「何が足りず、 どう直すか」を 1 行で示す。
#
#   Gradle を起動しないので、 Gradle がまだ動かない状態でも実行できる。
#   既存のビルドロジックには一切割り込まない (独立したスクリプト)。
#
#   使い方: ./doctor.sh
#   終了コード: 0 = FAIL 無し / 1 = FAIL あり
# =====================================================================
set -u
cd "$(dirname "$0")"

FAILED=0
ok()   { printf '  [ OK ]  %s\n' "$1"; }
warn() { printf '  [WARN]  %s\n     -> %s\n' "$1" "$2"; }
bad()  { printf '  [FAIL]  %s\n     -> %s\n' "$1" "$2"; FAILED=1; }

echo "=== 1. Git ==="
if command -v git >/dev/null 2>&1; then
    ok "git $(git --version | awk '{print $3}')"
else
    bad "git が見つかりません" "https://git-scm.com/downloads から導入してください"
fi

echo "=== 2. Java (Gradle ランチャ用) ==="
# デーモンの JVM は gradle/gradle-daemon-jvm.properties が決め、 無ければ
# Gradle が自動ダウンロードする。 ここで要るのは「ランチャを起動できる java」だけ。
JAVA_BIN=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java)"
fi
if [ -z "$JAVA_BIN" ]; then
    bad "java が見つかりません (JAVA_HOME も PATH も不可)" \
        "JDK 17 以上を入れて JAVA_HOME を設定するか PATH に通してください"
else
    JV="$("$JAVA_BIN" -version 2>&1 | head -n 1 | sed 's/.*version "\([^"]*\)".*/\1/')"
    MAJOR="$(printf '%s' "$JV" | awk -F. '{ if ($1=="1") print $2; else print $1 }' | tr -cd '0-9')"
    if [ -z "$MAJOR" ]; then
        warn "java の版を判定できません ($JV)" "手動で 17 以上か確認してください"
    elif [ "$MAJOR" -lt 17 ]; then
        warn "java $JV (17 未満)" \
             "起動はできる場合がありますが JDK 17 以上を推奨します"
    else
        ok "java $JV"
    fi
fi

echo "=== 3. リポジトリが供給するもの ==="
[ -f gradlew ] && ok "gradlew あり" || bad "gradlew がありません" "clone が壊れています"
[ -f gradle/wrapper/gradle-wrapper.jar ] && ok "gradle-wrapper.jar あり" \
    || bad "gradle/wrapper/gradle-wrapper.jar がありません" "clone が壊れています"
if [ -f gradle/gradle-daemon-jvm.properties ]; then
    ok "gradle-daemon-jvm.properties あり (デーモン JVM: $(grep '^toolchainVersion' gradle/gradle-daemon-jvm.properties | cut -d= -f2) / $(grep '^toolchainVendor' gradle/gradle-daemon-jvm.properties | cut -d= -f2))"
else
    warn "gradle/gradle-daemon-jvm.properties がありません" \
         "デーモン JVM がマシン依存になります"
fi

echo "=== 4. パス長 (Windows のみ問題になる) ==="
ROOT_LEN=$(pwd | wc -c)
ROOT_LEN=$((ROOT_LEN - 1))
case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*)
        # ビルド生成物の最深相対パスは実測 243 文字 (loom-cache の
        # minecraft-clientOnly-<hash>-<MC>-loom.mappings...jar.backup)。
        # 従来の MAX_PATH は 260 なので、 リポジトリのルートに使える余裕は
        # 260 - 243 - 1(区切り) = 16 文字しかない。 実質どこに clone しても
        # 足りないため、 Windows では長パス有効化が事実上の前提になる。
        LPE=""
        if command -v reg >/dev/null 2>&1; then
            LPE="$(reg query 'HKLM\SYSTEM\CurrentControlSet\Control\FileSystem' //v LongPathsEnabled 2>/dev/null \
                   | tr -d '\r' | awk '/LongPathsEnabled/ {print $NF}')"
        fi
        if [ "$LPE" = "0x1" ]; then
            ok "Windows LongPathsEnabled=1 (パス長 $ROOT_LEN 文字のルートでも可)"
        else
            bad "Windows の長パスが無効 (LongPathsEnabled=${LPE:-未設定}) / ルートは $ROOT_LEN 文字" \
                "生成物の最深相対パスが 243 文字あり 260 制限に収まりません。 管理者 PowerShell で: New-ItemProperty -Path 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\FileSystem' -Name LongPathsEnabled -Value 1 -PropertyType DWORD -Force"
        fi
        if command -v git >/dev/null 2>&1; then
            if [ "$(git config --get core.longpaths 2>/dev/null)" = "true" ]; then
                ok "git core.longpaths=true"
            else
                warn "git core.longpaths が true ではありません" \
                     "git config --global core.longpaths true (長いパスの checkout に必要)"
            fi
        fi
        ;;
    *)
        ok "非 Windows のためパス長制限なし (パス長 $ROOT_LEN 文字)"
        ;;
esac

echo "=== 5. ディスク空き ==="
if command -v df >/dev/null 2>&1; then
    AVAIL="$(df -Pk . 2>/dev/null | awk 'NR==2 {print int($4/1048576)}')"
    if [ -n "$AVAIL" ] && [ "$AVAIL" -lt 10 ]; then
        warn "空き ${AVAIL} GB" "初回ビルドで 5-10 GB 程度使います。 空けてください"
    else
        ok "空き ${AVAIL:-?} GB"
    fi
else
    warn "df が無く空き容量を確認できません" "手動で 10 GB 以上あるか確認してください"
fi

echo "=== 6. ネットワーク到達性 (初回ビルドに必須) ==="
HOSTS="services.gradle.org plugins.gradle.org repo.maven.apache.org \
maven.fabricmc.net meta.fabricmc.net launchermeta.mojang.com libraries.minecraft.net \
maven.kikugie.dev maven.terraformersmc.com maven.shedaniel.me api.modrinth.com api.foojay.io"
if command -v curl >/dev/null 2>&1; then
    UNREACHABLE=""
    for h in $HOSTS; do
        if ! curl -sS -m 12 -o /dev/null "https://$h" 2>/dev/null; then
            UNREACHABLE="$UNREACHABLE $h"
        fi
    done
    if [ -n "$UNREACHABLE" ]; then
        warn "到達できないホスト:$UNREACHABLE" \
             "社内プロキシ等で遮断されている可能性。 初回ビルドが失敗します"
    else
        ok "必須ホスト全て到達可 ($(printf '%s' "$HOSTS" | wc -w) 件)"
    fi
else
    warn "curl が無く到達性を確認できません" "手動で上記ホストへの HTTPS を確認してください"
fi

echo
if [ "$FAILED" -eq 0 ]; then
    echo "FAIL はありません。 './gradlew buildRecommended' を実行できます。"
    echo "(初回はおよそ 1 GB のダウンロードと 10 分前後がかかります)"
else
    echo "FAIL があります。 上の -> の指示を解消してください。"
fi
exit "$FAILED"
