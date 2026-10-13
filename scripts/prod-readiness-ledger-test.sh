#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/prod-readiness-ledger.sh"

fixture_root="$(mktemp -d)"
trap 'rm -rf "$fixture_root"' EXIT

mkdir -p "$fixture_root/app/src/main/resources" "$fixture_root/docs"

cat > "$fixture_root/app/src/main/resources/application.yml" <<'YAML'
plain: ${REAL_REQUIRED}
commented: value # ${COMMENT_ONLY}
double-quoted: "value # ${DOUBLE_QUOTED_REQUIRED}"
single-quoted: 'value # ${SINGLE_QUOTED_REQUIRED}'
fragment: https://example.com/#${URL_FRAGMENT_REQUIRED}
YAML
: > "$fixture_root/app/src/main/resources/application-prod.yml"

repo_root="$fixture_root"
prod_config_files=(
    "app/src/main/resources/application.yml"
    "app/src/main/resources/application-prod.yml"
)

expected_config="$(printf '%s\n' \
    DOUBLE_QUOTED_REQUIRED REAL_REQUIRED SINGLE_QUOTED_REQUIRED URL_FRAGMENT_REQUIRED | sort)"
actual_config="$(scan_prod_required_config)"
if [[ "$actual_config" != "$expected_config" ]]; then
    echo "prod 필수 설정 주석 처리 실패" >&2
    diff -u <(printf '%s\n' "$expected_config") <(printf '%s\n' "$actual_config") || true
    exit 1
fi

: > "$fixture_root/app/src/main/resources/application-extra.yml"
if scan_prod_required_config >/dev/null 2>&1; then
    echo "지원하지 않는 런타임 설정 파일을 허용했습니다." >&2
    exit 1
fi
rm "$fixture_root/app/src/main/resources/application-extra.yml"

ledger_doc="docs/ledger.md"

write_config_declarations() {
    {
        printf '%s\n' "$config_begin"
        printf '%s\n' '| 환경변수 | 주입 위치 | prod 상태 |'
        printf '%s\n' '| --- | --- | --- |'
        printf '%s\n' "$@"
        printf '%s\n' "$config_end"
    } > "$repo_root/$ledger_doc"
}

expect_config_declaration_failure() {
    local label="$1" required="$2"
    shift 2
    write_config_declarations "$@"
    if check_config_declarations "$required" >/dev/null 2>&1; then
        echo "prod 필수 설정 선언의 $label 검증을 통과했습니다." >&2
        exit 1
    fi
}

write_config_declarations '| `ALPHA_REQUIRED` | `secret` | `applied` |'
if ! check_config_declarations 'ALPHA_REQUIRED' >/dev/null 2>&1; then
    echo "유효한 prod 필수 설정 선언을 거부했습니다." >&2
    exit 1
fi

expect_config_declaration_failure "누락" $'ALPHA_REQUIRED\nBETA_REQUIRED' \
    '| `ALPHA_REQUIRED` | `secret` | `applied` |'
expect_config_declaration_failure "잉여" 'ALPHA_REQUIRED' \
    '| `ALPHA_REQUIRED` | `secret` | `applied` |' \
    '| `BETA_REQUIRED` | `configmap` | `required` |'
expect_config_declaration_failure "중복" 'ALPHA_REQUIRED' \
    '| `ALPHA_REQUIRED` | `secret` | `applied` |' \
    '| `ALPHA_REQUIRED` | `secret` | `applied` |'
expect_config_declaration_failure "환경변수 형식" 'alpha_required' \
    '| `alpha_required` | `secret` | `applied` |'
expect_config_declaration_failure "주입 위치 형식" 'ALPHA_REQUIRED' \
    '| `ALPHA_REQUIRED` | `file` | `applied` |'
expect_config_declaration_failure "상태 형식" 'ALPHA_REQUIRED' \
    '| `ALPHA_REQUIRED` | `secret` | `pending` |'

write_config_declarations '| `ALPHA_REQUIRED` | `secret` | `required` |'
if check_config_release >/dev/null 2>&1; then
    echo "미반영 필수 설정의 릴리스를 허용했습니다." >&2
    exit 1
fi
write_config_declarations '| `ALPHA_REQUIRED` | `secret` | `applied` |'
if ! check_config_release >/dev/null 2>&1; then
    echo "반영 완료 필수 설정의 릴리스를 차단했습니다." >&2
    exit 1
fi

# 선언 마커 쌍이 올바르게 닫히는지 검증합니다.
cat > "$repo_root/$ledger_doc" <<EOF
# fixture
$config_end
manual
$config_begin
tail
EOF
if check_marker_pair "$config_begin" "$config_end" "test" >/dev/null 2>&1; then
    echo "역순 마커를 허용했습니다." >&2
    exit 1
fi

cat > "$repo_root/$ledger_doc" <<EOF
# fixture
$config_begin
one
$config_end
manual
$config_end
EOF
if check_marker_pair "$config_begin" "$config_end" "test" >/dev/null 2>&1; then
    echo "중복 마커를 허용했습니다." >&2
    exit 1
fi

echo "prod-readiness-ledger 회귀 테스트 OK"
