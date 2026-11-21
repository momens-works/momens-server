#!/usr/bin/env bash
#
# OpenAPI Breaking Change 검사(MOM-1143)
#
# 두 OpenAPI 스냅샷을 비교해 호환되지 않는 변경이 감지되면 실패합니다.
# 감지된 변경 내역은 PR 화면에 annotation으로 표시하며,
# 승인 라벨에 따른 최종 통과 여부는 PR 워크플로에서 결정합니다.
#
# 검사 정책은 docs/spec/.oasdiff.yaml에서,
# Breaking Change 판정 기준은 docs/spec/api-versioning.md에서 관리합니다.
#
# 사용법:
#   openapi-breaking-change-check.sh <기준 스냅샷> <변경 스냅샷>
#
# 환경 변수:
#   OASDIFF_CONFIG  정책 설정 파일 경로입니다. 지정하지 않으면 docs/spec/.oasdiff.yaml을 사용합니다.

set -euo pipefail

# 동일한 태그가 다른 이미지를 가리킬 수 있으므로 서드파티 이미지는 digest로 고정합니다.
# 현재 고정 버전은 v1.38.0이며, 기존 알림 스크립트(openapi-change-notify.sh)는 아직 이전 이미지를 사용합니다.
readonly OASDIFF_IMAGE="tufin/oasdiff@sha256:d8e4cfbd36234af05c0d48f679005bace2a0253a93e5064158b720bf24ec4bd6"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 테스트에서 정책 파일의 복사본을 사용할 수 있도록 환경 변수로 정책 파일 경로를 지정할 수 있게 합니다.
config="${OASDIFF_CONFIG:-$repo_root/docs/spec/.oasdiff.yaml}"

main() {
    local base="${1:-}" head="${2:-}"

    [[ -n "$base" && -n "$head" ]] || {
        echo "사용법: openapi-breaking-change-check.sh <기준 스냅샷> <변경 스냅샷>" >&2
        return 2
    }
    [[ -f "$base" && -f "$head" && -f "$config" ]] || {
        echo "스냅샷 또는 정책 설정 파일을 찾을 수 없습니다. $base $head $config" >&2
        return 2
    }

    # 두 스냅샷을 하나의 디렉터리에 복사한 뒤 마운트합니다. 호출 시 전달한 파일 경로와 관계없이
    # 동일한 방식으로 실행하기 위한 처리입니다.
    local specs policy_dir status=0
    policy_dir="$(cd "$(dirname "$config")" && pwd)"
    specs="$(mktemp -d)"
    cp "$base" "$specs/base.json"
    cp "$head" "$specs/head.json"
    docker run --rm \
        -v "$specs:/specs:ro" \
        -v "$policy_dir:/policy:ro" \
        "$OASDIFF_IMAGE" \
        breaking /specs/base.json /specs/head.json \
        --config "/policy/$(basename "$config")" \
        --format githubactions || status=$?
    rm -rf "$specs"
    return "$status"
}

main "$@"
