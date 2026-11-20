#!/usr/bin/env bash
#
# prod 운영 준비 대장 (MOM-0841).
#
# prod 유효 설정의 기본값 없는 환경변수가 대장 문서에 선언됐는지 검사하고, 반영되지 않은 선언이
# 남은 릴리스를 차단한다. 규약은 docs/rules/configuration.md 를 본다.
#
# 사용법:
#   prod-readiness-ledger.sh --check          prod 필수 설정 선언을 검사한다
#   prod-readiness-ledger.sh --release-check  미반영 필수 설정이 남아 있으면 실패한다

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ledger_doc="docs/prod-readiness-ledger.md"
config_begin='<!-- BEGIN DECLARATION: prod-required-config -->'
config_end='<!-- END DECLARATION: prod-required-config -->'
prod_config_files=(
    "app/src/main/resources/application.yml"
    "app/src/main/resources/application-prod.yml"
)
supported_config_files=(
    "app/src/main/resources/application.yml"
    "app/src/main/resources/application-local.yml"
    "app/src/main/resources/application-dev.yml"
    "app/src/main/resources/application-test.yml"
    "app/src/main/resources/application-prod.yml"
)

check_marker_pair() {
    local begin="$1" end="$2" label="$3" begin_count end_count begin_line end_line
    begin_count="$(grep -cFx "$begin" "$repo_root/$ledger_doc")"
    end_count="$(grep -cFx "$end" "$repo_root/$ledger_doc")"

    if [[ "$begin_count" -ne 1 || "$end_count" -ne 1 ]]; then
        echo "::error::$ledger_doc 의 $label 구간 표시는 시작과 끝이 각각 하나여야 합니다."
        return 1
    fi

    begin_line="$(grep -nFx "$begin" "$repo_root/$ledger_doc" | cut -d: -f1)"
    end_line="$(grep -nFx "$end" "$repo_root/$ledger_doc" | cut -d: -f1)"
    if [[ "$begin_line" -ge "$end_line" ]]; then
        echo "::error::$ledger_doc 의 $label 시작 표시는 끝 표시보다 앞에 있어야 합니다."
        return 1
    fi
}

strip_yaml_comments() {
    awk '
        {
            output = ""
            single_quoted = 0
            double_quoted = 0
            escaped = 0

            for (position = 1; position <= length($0); position++) {
                character = substr($0, position, 1)
                previous = position == 1 ? "" : substr($0, position - 1, 1)
                comment_start = character == "#" && (position == 1 || previous ~ /[[:space:]]/)

                if (escaped) {
                    output = output character
                    escaped = 0
                    continue
                }
                if (double_quoted && character == "\\") {
                    output = output character
                    escaped = 1
                    continue
                }
                if (!double_quoted && character == "\047") {
                    single_quoted = !single_quoted
                } else if (!single_quoted && character == "\"") {
                    double_quoted = !double_quoted
                } else if (!single_quoted && !double_quoted && comment_start) {
                    break
                }
                output = output character
            }
            print output
        }
    ' "$@"
}

scan_prod_required_config() {
    local files=() file

    check_prod_config_scope || return 1
    for file in "${prod_config_files[@]}"; do
        if [[ ! -f "$repo_root/$file" ]]; then
            echo "필수 prod 설정 파일을 찾을 수 없습니다: $file" >&2
            return 1
        fi
        files+=("$repo_root/$file")
    done

    strip_yaml_comments "${files[@]}" \
        | grep -oE '\$\{[A-Z][A-Z0-9_]*\}' \
        | sed -E 's/^\$\{//; s/\}$//' \
        | sort -u
}

check_prod_config_scope() {
    local file relative allowed supported unsupported=""

    while IFS= read -r file; do
        relative="${file#"$repo_root/"}"
        supported=false
        for allowed in "${supported_config_files[@]}"; do
            if [[ "$relative" == "$allowed" ]]; then
                supported=true
                break
            fi
        done
        [[ "$supported" == "true" ]] || unsupported+="$relative"$'\n'
    done < <(
        find "$repo_root" -type d \( -name build -o -name .git -o -name .gradle-work \) -prune -o \
            -type f \( -path '*/src/main/resources/application*.yml' \
                -o -path '*/src/main/resources/application*.yaml' \) -print | sort
    )

    if [[ -n "$unsupported" ]]; then
        echo "지원하지 않는 런타임 설정 파일이 있습니다. prod 스캔 범위를 함께 갱신하세요:" >&2
        printf '%s' "$unsupported" | sed 's/^/  /' >&2
        return 1
    fi
}

config_declarations() {
    awk -F'|' -v begin="$config_begin" -v end="$config_end" '
        function trim(value) {
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
            gsub(/`/, "", value)
            return value
        }
        $0 == begin { inside = 1; next }
        $0 == end { inside = 0; next }
        inside && /^\|/ {
            key = trim($2)
            location = trim($3)
            status = trim($4)
            if (key == "환경변수" || key ~ /^-+$/) next
            print key "\t" location "\t" status
        }
    ' "$repo_root/$ledger_doc"
}

check_config_declarations() {
    local required="$1" declarations invalid duplicate declared_keys missing extra failed=0

    check_marker_pair "$config_begin" "$config_end" "prod 필수 설정 선언" || return 1
    declarations="$(config_declarations)"

    invalid="$(printf '%s\n' "$declarations" | awk -F'\t' '
        $1 !~ /^[A-Z][A-Z0-9_]*$/ ||
        ($2 != "secret" && $2 != "configmap") ||
        ($3 != "required" && $3 != "applied")
    ')"
    if [[ -n "$invalid" ]]; then
        failed=1
        echo "::error::prod 필수 설정 선언은 환경변수, secret|configmap, required|applied 형식이어야 합니다."
        printf '%s\n' "$invalid" | sed 's/^/  /'
    fi

    duplicate="$(printf '%s\n' "$declarations" | cut -f1 | sort | uniq -d)"
    if [[ -n "$duplicate" ]]; then
        failed=1
        echo "::error::prod 필수 설정 선언에 중복 키가 있습니다."
        printf '%s\n' "$duplicate" | sed 's/^/  /'
    fi

    declared_keys="$(printf '%s\n' "$declarations" | cut -f1 | sort -u)"
    missing="$(comm -23 <(printf '%s\n' "$required") <(printf '%s\n' "$declared_keys"))"
    extra="$(comm -13 <(printf '%s\n' "$required") <(printf '%s\n' "$declared_keys"))"

    if [[ -n "$missing" ]]; then
        failed=1
        echo "::error::prod 유효 설정에 있지만 대장에 선언되지 않은 필수 환경변수가 있습니다."
        printf '%s\n' "$missing" | sed 's/^/  /'
    fi
    if [[ -n "$extra" ]]; then
        failed=1
        echo "::error::대장에 선언됐지만 prod 유효 설정에서 요구하지 않는 환경변수가 있습니다."
        printf '%s\n' "$extra" | sed 's/^/  /'
    fi

    [[ "$failed" -eq 0 ]] && echo "prod 필수 설정 선언 OK"
    return "$failed"
}

check_config_release() {
    local declarations unresolved
    declarations="$(config_declarations)"
    unresolved="$(printf '%s\n' "$declarations" | awk -F'\t' '$3 == "required" { print $1 "\t" $2 }')"

    if [[ -n "$unresolved" ]]; then
        echo "::error::prod에 반영되지 않은 필수 설정이 남아 있어 릴리스할 수 없습니다."
        printf '%s\n' "$unresolved" | sed 's/^/  /'
        echo "  반영을 확인하고 prod 상태를 applied 로 바꾼 뒤 다시 시도하세요."
        return 1
    fi
    echo "미반영 필수 설정 없음"
    return 0
}

main() {
    local required_config status=0
    if ! required_config="$(scan_prod_required_config)"; then
        echo "::error::prod 필수 설정 스캔에 실패했습니다." >&2
        return 1
    fi

    case "${1:---check}" in
        --check)
            check_config_declarations "$required_config" || status=1
            ;;
        --release-check)
            check_config_declarations "$required_config" || status=1
            [[ "$status" -eq 0 ]] && { check_config_release || status=1; }
            ;;
        *)
            echo "알 수 없는 옵션: $1" >&2
            echo "사용법: $(basename "$0") [--check|--release-check]" >&2
            status=2
            ;;
    esac
    return "$status"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
