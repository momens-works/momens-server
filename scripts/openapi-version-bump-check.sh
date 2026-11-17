#!/usr/bin/env bash
#
# OpenAPI 스펙 버전 검사(MOM-1143)
#
# 두 OpenAPI 스냅샷을 비교해 호환되지 않는 변경이 발생했지만 스펙 버전(`info.version`)의 major가
# 증가하지 않은 경우 실패합니다. PR 단계에서 실행해 Breaking Change가 버전 증가 없이 머지되지
# 않도록 합니다.
#
# 변경 사항의 판정에는 oasdiff의 버전 검사 정책(`--fail-on MISSING-VERSION-BUMP`)을 사용합니다.
# 정책 설정은 `docs/spec/.oasdiff.yaml`, 판정 기준은 `docs/spec/api-versioning.md`에서 관리합니다.
#
# 사용법:
#   openapi-version-bump-check.sh <기준 스냅샷> <변경 스냅샷>
#
# 환경 변수:
#   OASDIFF_CONFIG  정책 설정 파일 경로입니다. 지정하지 않으면 docs/spec/.oasdiff.yaml을 사용합니다.

set -euo pipefail

# 서드파티 이미지는 digest로 고정합니다. 동일한 태그가 다른 이미지를 가리킬 수 있기 때문입니다.
# v1.38.0은 버전 검사 정책(`MISSING-VERSION-BUMP`)을 지원하는 첫 릴리스입니다. 기존 알림
# 스크립트(`openapi-change-notify.sh`)는 아직 이전 이미지를 사용합니다.
readonly OASDIFF_IMAGE="tufin/oasdiff@sha256:d8e4cfbd36234af05c0d48f679005bace2a0253a93e5064158b720bf24ec4bd6"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 테스트에서 저장소의 예외 파일을 변경하지 않고 복사본으로 검증할 수 있도록 정책 설정 파일 경로를
# 환경 변수로 지정할 수 있게 합니다.
config="${OASDIFF_CONFIG:-$repo_root/docs/spec/.oasdiff.yaml}"

main() {
    local base="${1:-}" head="${2:-}"

    [[ -n "$base" && -n "$head" ]] || {
        echo "사용법: openapi-version-bump-check.sh <기준 스냅샷> <변경 스냅샷>" >&2
        return 2
    }
    [[ -f "$base" && -f "$head" && -f "$config" ]] || {
        echo "스냅샷 또는 정책 설정 파일을 찾을 수 없습니다. $base $head $config" >&2
        return 2
    }

    # 두 스냅샷을 하나의 디렉터리에 복사한 뒤 마운트합니다. 호출 시 전달한 파일 경로와 관계없이
    # 동일한 방식으로 실행하기 위한 처리입니다.
    local specs status=0
    specs="$(mktemp -d)"
    cp "$base" "$specs/base.json"
    cp "$head" "$specs/head.json"
    docker run --rm \
        -v "$specs:/specs:ro" \
        -v "$(dirname "$config"):/policy:ro" \
        "$OASDIFF_IMAGE" \
        breaking /specs/base.json /specs/head.json \
        --config "/policy/$(basename "$config")" \
        --format githubactions || status=$?
    rm -rf "$specs"
    return "$status"
}

main "$@"
