#!/usr/bin/env bash
#
# openapi-change-notify.sh 회귀 테스트.
#
# 이 알림은 OpenAPI 스냅샷을 변경한 PR이 머지될 때만 실행된다. 잘못 구현해도 머지 전에는
# 문제가 드러나지 않으며, 잘못된 본문이 팀 채널에 전송된 뒤에야 발견할 수 있다.
# 따라서 변경 판정과 본문 생성 동작을 실제 메시지 전송 없이 이 스크립트에서 고정한다.
#
# oasdiff 호출은 OASDIFF_RUNNER에 지정한 함수로, 메시지 전송은 SEND_RUNNER에 지정한 함수로
# 대체한다. 따라서 docker와 네트워크 없이 실행할 수 있다.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
notify="$repo_root/scripts/openapi-change-notify.sh"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
: > "$work/base.json"
: > "$work/head.json"

failures=0

# 다음 테스트 실행에서 oasdiff 결과로 사용할 내용을 설정한다.
given_changes() {
    printf '%s' "$1" > "$work/changes.json"
}

fake_oasdiff() { cat "$OASDIFF_FIXTURE"; }
fake_send() { cat > "$SEND_CAPTURE"; }
export -f fake_oasdiff fake_send

# 알림 스크립트를 한 번 실행하고 표준 출력과 전송 내용을 기록한다.
run_notify() {
    rm -f "$work/sent.json"
    OASDIFF_RUNNER=fake_oasdiff SEND_RUNNER=fake_send \
    OASDIFF_FIXTURE="$work/changes.json" SEND_CAPTURE="$work/sent.json" \
    DISCORD_WEBHOOK_URL="${1:-}" \
    PR_NUMBER=191 PR_TITLE="${PR_TITLE:-태스크 연결}" PR_URL="${PR_URL:-https://example/pr/191}" \
        "$notify" "$work/base.json" "$work/head.json" 2>/dev/null
}

fail() {
    failures=$((failures + 1))
    printf 'FAIL  %s\n' "$1" >&2
}

expect_sent() {
    [[ -f "$work/sent.json" ]] || fail "$1: 전송했어야 합니다"
}

expect_not_sent() {
    [[ ! -f "$work/sent.json" ]] || fail "$1: 전송하지 않았어야 합니다"
}

expect_contains() {
    grep -qF "$2" <<< "$1" || fail "$3: 다음 내용이 있어야 합니다: $2"
}

expect_not_contains() {
    grep -qF "$2" <<< "$1" && fail "$3: 다음 내용이 없어야 합니다: $2"
    return 0
}

# 1. 클라이언트가 대응해야 하는 변경이 없으면 알림을 보내지 않는다. 설명 문구만 수정한 PR이 이에 해당한다.
given_changes '[]'
run_notify "https://example/webhook" >/dev/null
expect_not_sent "빈 결과"

# 2. 영향도가 높은 항목을 먼저 싣는다. 호환되지 않는 변경이 영향도가 낮은 항목 아래에 묻히면 안 된다.
given_changes '[
  {"id":"endpoint-added","text":"endpoint added","level":1,"operation":"POST","path":"/api/a"},
  {"id":"api-path-removed-without-deprecation","text":"api path removed","level":3,"operation":"DELETE","path":"/api/b"}
]'
body="$(run_notify)"
expect_contains "$body" "호환되지 않는 변경 1건" "영향도 정렬"
expect_contains "$body" "알림 1건" "영향도 정렬"
[[ "$(grep -n '호환되지 않는 변경' <<< "$body" | cut -d: -f1)" -lt \
   "$(grep -n '알림 1건' <<< "$body" | cut -d: -f1)" ]] || fail "영향도 정렬: 영향도가 높은 항목이 위에 있어야 합니다"

# 3. components 아래의 변경에는 operation과 path가 없다. 별도로 처리하지 않으면 본문에 null이 출력된다.
given_changes '[{"id":"api-schema-removed","text":"removed the schema `A`","level":1,"section":"components"}]'
body="$(run_notify)"
expect_not_contains "$body" "null" "path 없는 항목"
expect_contains "$body" "removed the schema" "path 없는 항목"

# 4. 글자 수 한도를 넘으면 해당 항목을 제외하고 남은 건수를 알린다.
long_path="$(printf '/api/%0.sx' {1..120})"
given_changes "$(jq -n --arg p "$long_path" '[range(0;40) | {id:"endpoint-added",text:"endpoint added",level:1,operation:"POST",path:$p}]')"
body="$(run_notify)"
expect_contains "$body" "건은 아래 PR에서 확인해 주세요" "글자 수 한도"
[[ "${#body}" -le 2000 ]] || fail "글자 수 한도: 본문이 2,000자를 넘었습니다(${#body})"

# 5. 머리말과 URL이 길어도 항목이나 링크를 중간에서 자르지 않는다. 항목에 쓸 길이를 고정값으로 두면
#    합계가 상한을 넘어 완성된 본문을 문자 단위로 자르게 되고, 그때 맨 끝의 PR 링크가 사라진다.
given_changes "$(jq -n '
  [range(0;3) | {id:"api-path-removed-without-deprecation",text:"api path removed without deprecation",level:3,operation:"DELETE",path:("/api/v1/workspaces/{workspaceId}/projects/{projectId}/tasks/" + (.|tostring))}]
  + [range(0;3) | {id:"x",text:"warn",level:2,operation:"POST",path:"/api/w"}]
  + [range(0;40) | {id:"endpoint-added",text:"endpoint added",level:1,operation:"POST",path:("/api/v1/workspaces/{workspaceId}/projects/{projectId}/tasks/" + (.|tostring))}]')"
long_title="$(printf '아주 긴 PR 제목입니다 %0.s' {1..20})"
long_url="https://github.com/momens-works/momens-server/pull/999"
body="$(PR_TITLE="$long_title" PR_URL="$long_url" run_notify)"
[[ "${#body}" -le 2000 ]] || fail "긴 머리말: 본문이 2,000자를 넘었습니다(${#body})"
expect_contains "$body" "$long_url>" "긴 머리말"

# 6. 웹훅 URL이 있으면 메시지를 전송하고, 없으면 본문만 출력한다.
given_changes '[{"id":"endpoint-added","text":"endpoint added","level":1,"operation":"POST","path":"/api/a"}]'
run_notify "https://example/webhook" >/dev/null
expect_sent "웹훅 URL 있음"
jq -e '.content | length > 0' "$work/sent.json" >/dev/null || fail "웹훅 URL 있음: content가 비어 있습니다"

run_notify >/dev/null
expect_not_sent "웹훅 URL 없음"

if [[ "$failures" -gt 0 ]]; then
    printf '\n실패 %d건\n' "$failures" >&2
    exit 1
fi
echo "openapi-change-notify.sh 회귀 테스트를 모두 통과했습니다."
