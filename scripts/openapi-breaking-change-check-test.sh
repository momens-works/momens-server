#!/usr/bin/env bash
#
# openapi-breaking-change-check.sh의 회귀 테스트입니다.
#
# 변경 사항은 oasdiff가 판정하고, 판정 기준은 `docs/spec`의 정책 파일에서 관리합니다. 정책 설정이
# 누락되면 호환되지 않는 변경도 검사를 통과할 수 있으므로, digest로 고정한 oasdiff 이미지와 실제
# 정책 파일을 사용해 판정 결과를 검증합니다. 테스트 실행에는 Docker가 필요합니다.
#
# 각 테스트는 기준 스냅샷에서 검증 대상 조건 하나만 변경해 실행합니다.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
check="$repo_root/scripts/openapi-breaking-change-check.sh"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

failures=0

cp "$repo_root/docs/spec/.oasdiff.yaml" "$repo_root/docs/spec/oasdiff-levels.txt" "$work/"

# 동일한 응답 스키마를 사용하는 경로 두 개를 정의합니다. `/api/mcp`는 검사 제외 경로입니다.
cat > "$work/base.json" <<'JSON'
{
  "openapi": "3.1.0",
  "info": { "title": "momens-server", "version": "v1" },
  "paths": {
    "/api/items": {
      "get": {
        "operationId": "listItems",
        "responses": {
          "200": {
            "description": "ok",
            "content": {
              "application/json": {
                "schema": {
                  "type": "object",
                  "properties": { "id": { "type": "string" }, "name": { "type": "string" } }
                }
              }
            }
          }
        }
      }
    },
    "/api/mcp": {
      "post": {
        "operationId": "mcp",
        "responses": {
          "200": {
            "description": "ok",
            "content": {
              "application/json": {
                "schema": {
                  "type": "object",
                  "properties": { "id": { "type": "string" }, "name": { "type": "string" } }
                }
              }
            }
          }
        }
      }
    }
  }
}
JSON

remove_name() {
    printf 'del(.paths["%s"].%s.responses["200"].content["application/json"].schema.properties.name)' "$1" "$2"
}

given_head() {
    jq "$1" "$work/base.json" > "$work/head.json"
}

fail() {
    failures=$((failures + 1))
    printf 'FAIL  %s\n' "$1" >&2
}

expect_exit() {
    local expected="$1" label="$2" actual=0
    OASDIFF_CONFIG="$work/.oasdiff.yaml" "$check" "$work/base.json" "$work/head.json" >/dev/null 2>&1 || actual=$?
    [[ "$actual" -eq "$expected" ]] || fail "$label: 종료 코드 불일치: 기대값 $expected, 실제값 $actual"
}

# 1. 응답 필드를 삭제하면 검사가 실패해야 합니다.
#    응답 필드 삭제의 기본 심각도는 info이므로,
#    oasdiff-levels.txt에서 심각도를 조정하지 않으면 검사를 통과합니다.
given_head "$(remove_name /api/items get)"
expect_exit 1 "응답 필드 삭제"

# 2. 검사 제외 경로에서 동일한 변경이 발생하면 검사를 통과해야 합니다.
given_head "$(remove_name /api/mcp post)"
expect_exit 0 "검사 제외 경로"

# 3. 호환되는 변경인 optional 쿼리 파라미터 추가는 검사를 통과해야 합니다.
given_head '.paths["/api/items"].get.parameters = [{"name": "q", "in": "query", "required": false, "schema": {"type": "string"}}]'
expect_exit 0 "호환되는 변경"

if [[ "$failures" -gt 0 ]]; then
    printf '\n테스트 실패: %d건\n' "$failures" >&2
    exit 1
fi
echo "openapi-breaking-change-check.sh 회귀 테스트를 모두 통과했습니다."
