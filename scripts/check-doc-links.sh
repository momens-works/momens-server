#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

FILES=()
# 새 문서도 stage 이전부터 검사합니다. Git 제외 파일과 생성물은 입력에 넣지 않습니다.
while IFS= read -r -d '' file; do
  # 삭제한 문서는 입력에서 빼되, 다른 문서에서 들어오는 링크는 계속 검사합니다.
  if [[ -f "$file" ]]; then
    FILES+=("./${file}")
  fi
done < <(git ls-files --cached --others --exclude-standard -z -- '*.md')

if [[ ${#FILES[@]} -eq 0 ]]; then
  echo "No Markdown files to check"
  exit 0
fi

LYCHEE="$(bash "${SCRIPT_DIR}/install-lychee.sh")"
echo "Checking ${#FILES[@]} Markdown files (local files and anchors)"
# 입력 목록은 Git이 결정합니다. 사용자 ignore/config가 CI와 다른 결과를 만들지 않게 합니다.
exec "$LYCHEE" --config /dev/null --offline --include-fragments=anchor-only \
  --hidden --no-ignore --no-progress -- "${FILES[@]}"
