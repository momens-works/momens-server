#!/usr/bin/env bash
#
# `openapi-version-bump-check.sh`의 회귀 테스트입니다.
#
# 변경 사항은 oasdiff가 판정하고, 판정 기준은 `docs/spec`의 정책 파일에서 관리합니다. 정책 설정이
# 누락되면 호환되지 않는 변경도 검사를 통과할 수 있으므로, digest로 고정한 oasdiff 이미지와 실제
# 정책 파일을 사용해 판정 결과를 검증합니다. 테스트 실행에는 Docker가 필요합니다.
#
# 동일한 응답 필드 삭제를 기준으로 각 테스트에서 하나의 조건만 변경해 검증합니다.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
check="$repo_root/scripts/openapi-version-bump-check.sh"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

failures=0

# 저장소의 정책 파일을 복사해 사용합니다. 4번 테스트에서 예외 항목을 추가하더라도 원본 예외 파일은
# 변경하지 않습니다.
cp "$repo_root/docs/spec/.oasdiff.yaml" "$repo_root"/docs/spec/oasdiff-*.txt "$work/"

# 동일한 응답 스키마를 사용하는 경로 두 개를 정의합니다. `/api/mcp`는 검사 제외 경로입니다.
cat > "$work/base.json" <<'JSON'
{
  "openapi": "3.1.0",
  "info": { "title": "momens-server", "version": "1.0.0" },
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

# 1. 응답 필드를 삭제하고 스펙 버전을 유지하면 실패합니다. 응답 필드 삭제의 기본 심각도는 `info`이므로
#    `oasdiff-levels.txt`에서 심각도를 조정하지 않으면 검사를 통과합니다.
given_head "$(remove_name /api/items get)"
expect_exit 1 "응답 필드 삭제, 버전 유지"

# 2. 동일한 변경에서 major를 증가시키면 통과합니다.
given_head "$(remove_name /api/items get) | .info.version = \"2.0.0\""
expect_exit 0 "응답 필드 삭제, major 증가"

# 3. 동일한 변경에서 minor만 증가시키면 실패합니다. Breaking Change에는 major 증가가 필요합니다.
given_head "$(remove_name /api/items get) | .info.version = \"1.1.0\""
expect_exit 1 "응답 필드 삭제, minor 증가"

# 4. 동일한 변경을 예외 파일에 등록하면 버전을 올리지 않아도 통과합니다.
given_head "$(remove_name /api/items get)"
printf '%s\n' '- 2026-11-17 #0 in API GET /api/items removed the optional property `name` from the response with the `200` status' \
    >> "$work/oasdiff-ignore.txt"
expect_exit 0 "예외 등록"
cp "$repo_root/docs/spec/oasdiff-ignore.txt" "$work/oasdiff-ignore.txt"

# 5. 검사 제외 경로에서 동일한 변경이 발생하면 통과합니다.
given_head "$(remove_name /api/mcp post)"
expect_exit 0 "검사 제외 경로"

# 6. 호환되는 변경인 optional 쿼리 파라미터 추가는 버전을 올리지 않아도 통과합니다.
given_head '.paths["/api/items"].get.parameters = [{"name": "q", "in": "query", "required": false, "schema": {"type": "string"}}]'
expect_exit 0 "호환되는 변경"

if [[ "$failures" -gt 0 ]]; then
    printf '\n테스트 실패: %d건\n' "$failures" >&2
    exit 1
fi
echo "openapi-version-bump-check.sh 회귀 테스트를 모두 통과했습니다."
