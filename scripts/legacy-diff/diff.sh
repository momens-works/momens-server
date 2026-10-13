#!/usr/bin/env bash
# 같은 요청을 레거시 momens-api 와 신규 momens-server 에 보내고 응답을 대조합니다(MOM-0877).
# write 요청과 요청 이후의 DB 기록 대조를 함께 지원합니다(MOM-0882).
#
# 로컬 모드는 고정 fixture를 사용합니다. 기본 비교는 JSON 키만 정렬하며 배열 순서는 유지합니다.
# cases.tsv의 ignore는 응답 필드를 제외하고, scrub은 비결정적 값의 동일성 구조를 남겨 비교합니다.
#
# write 케이스는 --local-stack 에서만 실행합니다. 픽스처 되돌리기와 DB 기록 비교가 이 스크립트가
# 아는 로컬 compose 스택을 전제하고, dev 실서버를 가리킨 채로 돌면 실데이터를 바꾸기 때문입니다.
# run.sh 가 이 플래그를 넘깁니다.
#
# 판정 기준은 golden 입니다(MOM-0881). "레거시와 다르면 차이"가 아니라 "golden 과 다르면 실패"라서,
# 계약이 확정한 의도된 차이는 golden 안에 이미 들어 있고 새로 생긴 차이만 실패로 드러납니다.
# golden 갱신은 --update-golden 을 명시해야 일어납니다. 실패를 갱신으로 조용히 덮지 않기 위해서입니다.
#
# 값이 벽시계나 무작위 UUID 라 golden 에 담을 수 없을 때는 cases.tsv 의 scrub 열을 씁니다. 값은
# 지우되 같은 값은 같은 토큰이 되어 동일성 구조가 남습니다.
#
# 종료 코드가 판정이므로 "아무것도 검증하지 않았는데 0" 을 막습니다. --only 가 아무 행에도 매칭되지
# 않으면 exit 2 입니다. cases.tsv 에 없는 케이스 디렉터리는 경고로 알리되 종료 코드는 건드리지
# 않습니다. 판정을 망치는 것이 아니라 정리가 안 된 상태이기 때문입니다.
#
# dev 실서버를 대상으로 할 때만 --normalize 로 UUID·타임스탬프를 자리표시자로 바꿉니다. 이 모드는
# 값 비교를 포기하는 대신 shape 비교만 남깁니다. golden 판정도 함께 꺼집니다.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

legacy_base="http://localhost:18080"
server_base="http://localhost:18081"
cases_file="${here}/cases.tsv"
cases_dir="${here}/cases"
only=""
normalize=0
local_stack=0
update_golden=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --legacy-base) legacy_base="$2"; shift 2 ;;
    --server-base) server_base="$2"; shift 2 ;;
    --cases) cases_file="$2"; shift 2 ;;
    --only) only="$2"; shift 2 ;;
    --normalize) normalize=1; shift ;;
    --local-stack) local_stack=1; shift ;;
    --update-golden) update_golden=1; shift ;;
    -h|--help)
      # 주석 길이가 바뀌어도 코드가 도움말에 섞이지 않도록 첫 줄 다음의 연속된 주석을 읽습니다.
      awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
      exit 0 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
  esac
done

: "${MOMENS_DIFF_JWT_SECRET:?MOMENS_DIFF_JWT_SECRET 가 필요합니다 (harness.conf 참고)}"

# --local-stack 은 픽스처 되돌리기와 DB 비교를 compose 스택의 DB 에 직접 실행합니다. 그래서 base URL
# 까지 로컬이어야 전제가 성립합니다. 플래그만 보면 --local-stack 에 원격 base 를 함께 줄 수 있는데,
# 그러면 write 는 원격에 나가고 검증은 로컬 DB 를 보게 됩니다. 실데이터를 바꾸면서 비교 결과까지
# 무의미해지는 조합이라 여기서 막습니다.
#
# 포트가 아니라 host 만 봅니다. 포트는 harness.conf 가 소유해 바뀔 수 있지만, compose 가 127.0.0.1
# 에만 바인딩하므로 "로컬인가"는 host 로 판별됩니다.
if [[ "$local_stack" -eq 1 ]]; then
  for base in "$legacy_base" "$server_base"; do
    host="${base#*://}"
    host="${host%%/*}"
    host="${host%%:*}"
    if [[ "$host" != "localhost" && "$host" != "127.0.0.1" ]]; then
      echo "--local-stack 은 로컬 compose 스택 전용입니다. 로컬이 아닌 base URL: ${base}" >&2
      exit 2
    fi
  done
fi

# fixture.sql 의 사용자 키 -> UUID
user_uuid() {
  case "$1" in
    owner)    echo "00000000-0000-4000-8000-000000000001" ;;
    member)   echo "00000000-0000-4000-8000-000000000002" ;;
    stranger) echo "00000000-0000-4000-8000-000000000003" ;;
    nobody)   echo "00000000-0000-4000-8000-000000000004" ;;
    *)        echo "" ;;
  esac
}

legacy_psql() {
  docker compose -f "${here}/compose.yml" exec -T legacy-db \
    psql -X -v ON_ERROR_STOP=1 -U momens -d momens_legacy "$@"
}

server_psql() {
  docker compose -f "${here}/compose.yml" exec -T server-db \
    psql -X -v ON_ERROR_STOP=1 -U momens -d momens_server "$@"
}

# 양쪽 DB 를 픽스처 상태로 되돌립니다. fixture.sql 은 TRUNCATE ... CASCADE 로 시작해 멱등하므로
# 그대로 다시 적용하면 됩니다. 케이스 사이의 실행 순서 의존을 없애는 것이 목적이라, 되돌리기가
# 실패하면 이후 비교는 의미가 없으므로 호출 측에서 즉시 멈춥니다.
reset_fixture() {
  legacy_psql -q < "${here}/fixture.sql" >/dev/null || return 1
  server_psql -q < "${here}/fixture.sql" >/dev/null || return 1
}

# 응답 본문을 비교 가능한 형태로 만듭니다. JSON 이 아니면 원문을 그대로 둡니다.
canonicalize() {
  local body="$1" ignore="${2:-}"
  if ! printf '%s' "$body" | jq -e . >/dev/null 2>&1; then
    printf '%s' "$body"
    return
  fi
  printf '%s' "$body" | jq -S --arg ig "$ignore" --argjson norm "$normalize" '
    def drop($ks):
      walk(if type == "object"
           then with_entries(select(.key as $k | $ks | index($k) | not))
           else . end);
    def scrub:
      if type == "string" then
        if test("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") then "<uuid>"
        elif test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T") then "<timestamp>"
        else . end
      elif type == "array" then map(scrub)
      elif type == "object" then with_entries(.value |= scrub)
      else . end;
    ($ig | split(",") | map(select(length > 0))) as $ks
    | (if ($ks | length) > 0 then drop($ks) else . end)
    | (if $norm == 1 then scrub else . end)'
}

# 비결정적 값을 등장 순서 자리표시자로 바꿉니다(MOM-0881).
#
# 값을 지우되 같은 값은 같은 토큰, 다른 값은 다른 토큰이 되어 동일성 구조가 남습니다. 모든
# 타임스탬프를 <timestamp> 하나로 뭉개면 안 됩니다. created_at 까지 함께 치환돼 "레거시는 두 시각이
# 다르고 신규는 같다"는 원장 확정 사실이 사라지고, 케이스가 매 실행 초록으로 통과합니다.
#
#   레거시  2026-01-01 …, 2026-08-19 …  ->  Time_1, Time_2
#   신규    2026-01-01 …, 2026-01-01 …  ->  Time_1, Time_1
#
# 컬럼 이름이 아니라 값의 모양으로 찾습니다. DB 출력이 CSV 라 이름으로 셀을 짚으려면 파싱이
# 필요한데, 모양 기반이면 응답 body 와 DB 행에 같은 함수를 쓰고 컬럼명을 모르는 값(token_hash 등)도
# 잡힙니다.
#
# 맵은 한 번의 호출 안에서만 유효합니다. 양쪽을 따로 치환해야 각 측의 구조가 그대로 남습니다.
scrub_awk='
function scrub_type(s, re, prefix,   out, val) {
  out = ""
  while (match(s, re)) {
    val = substr(s, RSTART, RLENGTH)
    if (!((prefix SUBSEP val) in tok)) {
      cnt[prefix]++
      tok[prefix, val] = prefix "_" cnt[prefix]
    }
    out = out substr(s, 1, RSTART - 1) tok[prefix, val]
    s = substr(s, RSTART + RLENGTH)
  }
  return out s
}
BEGIN {
  n = split(types, t, ",")
  for (i = 1; i <= n; i++) want[t[i]] = 1
  TS = "[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?(Z|[+-][0-9]{2}(:?[0-9]{2})?)?"
  UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
}
{
  line = $0
  if ("uuid" in want) line = scrub_type(line, UUID, "Uuid")
  if ("time" in want) line = scrub_type(line, TS, "Time")
  print line
}'

scrub_text() {
  local text="$1" types="$2"
  if [[ -z "$types" ]]; then
    printf '%s' "$text"
    return
  fi
  printf '%s\n' "$text" | awk -v types="$types" "$scrub_awk"
}

# status 와 body 를 한 번의 curl 로 받습니다.
#
# curl 의 종료 상태를 그대로 넘깁니다. 연결 실패에도 -w 는 status 000 을 출력하므로, 실패를
# 삼키면 두 서버가 모두 죽었을 때 000 과 빈 body 가 일치해 전 케이스가 "동일"로 집계됩니다.
# 이 도구는 잘못된 확신을 없애려고 존재하므로 그 결과가 가장 나쁩니다.
call() {
  local base="$1" path="$2" token="$3" method="$4" body_file="$5"
  local args=(-sS --connect-timeout 5 --max-time 30 -o - -w '\n%{http_code}'
              -X "$method" "${base}${path}" -H 'API-Version: 1')
  [[ -n "$token" ]] && args+=(-H "Cookie: session_token=${token}")
  [[ -n "$body_file" ]] && args+=(-H 'Content-Type: application/json' --data-binary "@${body_file}")
  curl "${args[@]}"
}

# golden 판정은 픽스처가 값을 고정하는 로컬 스택에서만 성립합니다. dev 실서버를 가리키거나
# --normalize 로 값 비교를 포기한 모드에서는 예전처럼 레거시 대 신규 diff 만 출력합니다.
use_golden=0
[[ "$local_stack" -eq 1 && "$normalize" -eq 0 ]] && use_golden=1

if [[ "$update_golden" -eq 1 && "$use_golden" -eq 0 ]]; then
  echo "--update-golden 은 --local-stack 에서만 씁니다. golden 은 픽스처가 값을 고정한 상태를 전제합니다." >&2
  exit 2
fi

pass=0
fail=0
skipped=0
updated=0
# --only 필터를 통과한 행 수. 0 이면 오타이므로 초록으로 끝내지 않습니다.
seen=0
# 직전 케이스가 DB 를 건드렸는지. write 뒤에 오는 read 케이스도 오염되므로 케이스 위치와 무관하게
# 되돌립니다. cases.tsv 의 행 순서에 의존하지 않기 위해서입니다.
#
# 1 로 시작하는 것은 오염이 프로세스 경계를 넘기 때문입니다. 마지막 케이스가 write 면 되돌리기
# 없이 끝나므로, --only 로 write 를 돌린 뒤 --only 로 read 를 돌리면 두 번째 실행이 첫 번째의
# 잔재 위에서 시작합니다. KEEP=1 이 권하는 반복 실행이 정확히 그 경로입니다.
dirty=1

while IFS=$'\t' read -r id as method legacy_path server_path ignore scrub; do
  [[ -z "${id// }" || "${id:0:1}" == "#" ]] && continue
  [[ -n "$only" && "$id" != "$only" ]] && continue
  seen=$((seen + 1))

  ignore="${ignore:-}"
  [[ "$ignore" == "-" ]] && ignore=""
  scrub="${scrub:-}"
  [[ "$scrub" == "-" ]] && scrub=""

  case_dir="${cases_dir}/${id}"
  body_file=""
  [[ -f "${case_dir}/body.json" ]] && body_file="${case_dir}/body.json"
  check_file=""
  [[ -f "${case_dir}/check.sql" ]] && check_file="${case_dir}/check.sql"

  is_write=0
  [[ "$method" != "GET" ]] && is_write=1

  # write 는 대상 DB 를 바꿉니다. dev 실서버를 가리킨 상태에서 돌면 실데이터를 건드리므로 막습니다.
  if [[ "$is_write" -eq 1 && "$local_stack" -eq 0 ]]; then
    echo "⏭  ${id}: write 케이스는 --local-stack 에서만 실행합니다 (${method})"
    skipped=$((skipped + 1))
    continue
  fi

  if [[ "$local_stack" -eq 1 && ( "$is_write" -eq 1 || "$dirty" -eq 1 ) ]]; then
    if ! reset_fixture; then
      echo "픽스처 되돌리기 실패: ${id}" >&2
      exit 1
    fi
    dirty=0
  fi

  token=""
  if [[ "$as" != "none" ]]; then
    uuid="$(user_uuid "$as")"
    if [[ -z "$uuid" ]]; then
      echo "⏭  ${id}: 알 수 없는 사용자 키 '${as}'"
      skipped=$((skipped + 1))
      continue
    fi
    token="$("${here}/mint-token.sh" "$uuid")"
  fi

  # 전송 실패는 케이스 실패가 아니라 비교 자체가 성립하지 않는 상태이므로 즉시 멈춥니다.
  if ! legacy_raw="$(call "$legacy_base" "$legacy_path" "$token" "$method" "$body_file")"; then
    echo "레거시 요청 실패: ${method} ${legacy_base}${legacy_path}" >&2
    exit 1
  fi
  if ! server_raw="$(call "$server_base" "$server_path" "$token" "$method" "$body_file")"; then
    echo "신규 서버 요청 실패: ${method} ${server_base}${server_path}" >&2
    exit 1
  fi
  [[ "$is_write" -eq 1 ]] && dirty=1

  legacy_status="${legacy_raw##*$'\n'}"
  server_status="${server_raw##*$'\n'}"
  legacy_body="${legacy_raw%$'\n'*}"
  server_body="${server_raw%$'\n'*}"

  legacy_json="$(scrub_text "$(canonicalize "$legacy_body" "$ignore")" "$scrub")"
  server_json="$(scrub_text "$(canonicalize "$server_body" "$ignore")" "$scrub")"

  echo "── ${id}  (as ${as})"
  echo "   legacy  ${legacy_status}  ${method} ${legacy_path}"
  echo "   server  ${server_status}  ${method} ${server_path}"
  [[ -n "$ignore" ]] && echo "   응답 무시 필드: ${ignore}"
  [[ -n "$scrub" ]] && echo "   자리표시자 치환: ${scrub}"

  body_diff="$(diff -u <(printf '%s\n' "$legacy_json") <(printf '%s\n' "$server_json") | tail -n +3)"

  # DB 기록 비교. 응답만 같고 기록이 다른 경우를 잡는 것이 write 케이스의 핵심이라, 케이스가
  # check.sql 을 소유하고 양쪽 DB 에 그대로 실행합니다. 안 볼 컬럼은 SELECT 목록에서 빼면
  # 되므로 응답과 달리 별도의 무시 목록이 없습니다.
  db_diff=""
  db_checked=0
  if [[ -n "$check_file" ]]; then
    if [[ "$local_stack" -eq 0 ]]; then
      echo "   ⏭ DB 비교는 --local-stack 에서만 합니다"
    else
      if ! legacy_rows="$(legacy_psql --csv < "$check_file")"; then
        echo "레거시 DB 조회 실패: ${check_file}" >&2
        exit 1
      fi
      if ! server_rows="$(server_psql --csv < "$check_file")"; then
        echo "신규 DB 조회 실패: ${check_file}" >&2
        exit 1
      fi
      db_checked=1
      legacy_rows="$(scrub_text "$legacy_rows" "$scrub")"
      server_rows="$(scrub_text "$server_rows" "$scrub")"
      db_diff="$(diff -u <(printf '%s\n' "$legacy_rows") <(printf '%s\n' "$server_rows") | tail -n +3)"
    fi
  fi

  # 케이스의 관찰 결과를 한 덩어리로 조립합니다. 이것이 golden 과 대조하는 단위입니다.
  # 레거시와 신규가 다르다는 사실 자체가 아니라, 그 차이의 모양이 계약이 확정한 것과 같은지를
  # 봐야 하기 때문에 diff 를 결과에 그대로 담습니다.
  actual="legacy_status: ${legacy_status}"$'\n'"server_status: ${server_status}"$'\n'"--- body ---"$'\n'
  if [[ -n "$body_diff" ]]; then actual+="$body_diff"; else actual+="동일"; fi
  if [[ "$db_checked" -eq 1 ]]; then
    actual+=$'\n'"--- db ---"$'\n'
    if [[ -n "$db_diff" ]]; then actual+="$db_diff"; else actual+="동일"; fi
  fi

  if [[ "$use_golden" -eq 0 ]]; then
    # dev 실서버 모드. 값이 고정되지 않아 golden 이 성립하지 않으므로 예전처럼 diff 만 출력합니다.
    if [[ "$legacy_status" == "$server_status" && -z "$body_diff" && -z "$db_diff" ]]; then
      [[ "$db_checked" -eq 1 ]] && echo "   ✓ 동일 (응답·DB)" || echo "   ✓ 동일"
      pass=$((pass + 1))
    else
      [[ "$legacy_status" != "$server_status" ]] && echo "   ✗ status 차이: ${legacy_status} → ${server_status}"
      if [[ -n "$body_diff" ]]; then
        echo "   ✗ body 차이 (- 레거시 / + 신규)"
        printf '%s\n' "$body_diff" | sed 's/^/     /'
      fi
      # db_diff 는 판정에 들어가므로 출력도 함께 있어야 합니다. 빠뜨리면 --local-stack --normalize
      # 조합에서 DB 기록만 갈릴 때 이유 없이 실패합니다. 설명 없는 실패는 이 도구가 없애려는 것과
      # 같은 종류의 문제입니다.
      if [[ -n "$db_diff" ]]; then
        echo "   ✗ DB 기록 차이 (- 레거시 / + 신규)"
        printf '%s\n' "$db_diff" | sed 's/^/     /'
      fi
      fail=$((fail + 1))
    fi
    echo
    continue
  fi

  golden_file="${case_dir}/golden.txt"
  if [[ "$update_golden" -eq 1 ]]; then
    mkdir -p "$case_dir"
    printf '%s\n' "$actual" > "$golden_file"
    echo "   ✎ golden 갱신"
    updated=$((updated + 1))
  elif [[ ! -f "$golden_file" ]]; then
    echo "   ✗ golden 이 없습니다: --update-golden 으로 만드세요"
    printf '%s\n' "$actual" | sed 's/^/     /'
    fail=$((fail + 1))
  else
    golden_diff="$(diff -u "$golden_file" <(printf '%s\n' "$actual") | tail -n +3)"
    if [[ -z "$golden_diff" ]]; then
      echo "   ✓ golden 일치"
      pass=$((pass + 1))
    else
      echo "   ✗ golden 불일치 (- golden / + 이번 실행)"
      printf '%s\n' "$golden_diff" | sed 's/^/     /'
      fail=$((fail + 1))
    fi
  fi
  echo
done < "$cases_file"

# 아무 행에도 매칭되지 않은 --only 는 오타입니다. 그대로 두면 0 건을 돌고 exit 0 으로 끝나
# "검증했다"로 읽힙니다. 종료 코드가 판정인 이상 가장 나쁜 실패 방식입니다.
if [[ -n "$only" && "$seen" -eq 0 ]]; then
  echo "--only '${only}' 에 해당하는 케이스가 없습니다. cases.tsv 의 id 를 확인하세요." >&2
  exit 2
fi

# cases.tsv 에서 행을 지우거나 id 를 바꾸면 cases/<id>/ 가 남습니다. 판정을 망치지는 않지만 아무도
# 모른 채 쌓이므로 알리기만 합니다. --only 와 무관하게 cases.tsv 소속 여부로만 판단합니다.
orphans=""
orphan_count=0
if [[ -d "$cases_dir" ]]; then
  while IFS= read -r dir; do
    dir_id="$(basename "$dir")"
    if ! awk -F'\t' -v want="$dir_id" '!/^#/ && NF && $1 == want { found = 1 } END { exit !found }' "$cases_file"; then
      orphans+="${dir_id} "
      orphan_count=$((orphan_count + 1))
    fi
  done < <(find "$cases_dir" -mindepth 1 -maxdepth 1 -type d | sort)
fi
if [[ "$orphan_count" -gt 0 ]]; then
  echo "⚠ cases.tsv 에 없는 케이스 디렉터리 ${orphan_count}개: ${orphans% }"
  echo "  행을 지웠거나 id 를 바꾼 흔적입니다. 판정에는 영향이 없습니다."
  echo
fi

if [[ "$update_golden" -eq 1 ]]; then
  echo "golden 갱신 ${updated} · 건너뜀 ${skipped}"
  echo
  echo "갱신된 golden 이 계약 문서가 확정한 내용과 맞는지 PR diff 로 확인하세요."
elif [[ "$use_golden" -eq 1 ]]; then
  echo "golden 일치 ${pass} · 불일치 ${fail} · 건너뜀 ${skipped}"
  [[ "$fail" -gt 0 ]] && echo && echo "불일치는 계약 문서나 구현 중 하나가 바뀌었다는 뜻입니다. 의도한 변경이면 --update-golden 으로 갱신하세요."
else
  echo "동일 ${pass} · 차이 ${fail} · 건너뜀 ${skipped}"
  echo
  echo "차이가 났다는 것 자체는 실패가 아닙니다. 계약 문서가 확정한 의도된 차이인지 대조하세요."
fi

[[ "$fail" -gt 0 ]] && exit 1
exit 0
