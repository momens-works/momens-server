#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_REPO="$(mktemp -d "${TMPDIR:-/tmp}/momens-doc-links-test.XXXXXX")"
trap 'rm -rf -- "$TEST_REPO"' EXIT
cd "$TEST_REPO"
git init -q
mkdir -p docs assets .codex/skills/example .claude/skills/example
printf 'image' > assets/example.png
printf '# 공백 경로\n' > 'docs/with space.md'
cat > docs/target.md <<'EOF'
# 한글 제목

## 중복
## 중복

## `inline` 코드

<a id="explicit"></a>

Setext 제목
===========
EOF
cat > README.md <<'EOF'
# 시작

[같은 문서](#시작)
[상대 경로](docs/target.md#한글-제목)
[중복 제목](docs/target.md#중복-1)
[코드 제목](docs/target.md#inline-코드)
[명시적 앵커](docs/target.md#explicit)
[Setext](docs/target.md#setext-제목)
[공백 경로](<docs/with space.md>)
[참조형][target]
![이미지](assets/example.png)
<img src="assets/example.png" alt="HTML 이미지">
[외부 URL](https://invalid.invalid/not-checked)

[target]: docs/target.md#한글-제목

`[인라인 코드](missing-inline.md)`

```markdown
[예시](missing-fenced.md)
```

    [들여쓴 코드](missing-indented.md)
EOF
printf '[규칙](../../../docs/target.md#한글-제목)\n' > .codex/skills/example/SKILL.md
cp .codex/skills/example/SKILL.md .claude/skills/example/SKILL.md
printf 'ignored/\n' > .gitignore
mkdir ignored
printf '[제외](missing.md)\n' > ignored/example.md
git add .

check() { bash "${SCRIPT_DIR}/check-doc-links.sh" > "$TEST_REPO/result.log" 2>&1; }
pass() {
  if ! check; then
    cat "$TEST_REPO/result.log" >&2
    echo "FAIL: $1" >&2
    exit 1
  fi
  echo "PASS: $1"
}
fail() {
  if check; then
    echo "FAIL: expected rejection: $1" >&2
    exit 1
  fi
  if ! grep -Fq "$2" "$TEST_REPO/result.log"; then
    cat "$TEST_REPO/result.log" >&2
    echo "FAIL: missing diagnostic: $2" >&2
    exit 1
  fi
  echo "PASS: $1"
}

pass 'valid paths, images, Korean/duplicate/HTML anchors, code examples, ignored files'
printf '[새 파일](missing-new.md)\n' > new.md
fail 'new untracked document' 'missing-new.md'
rm new.md
printf '[잘못된 앵커](target.md#없는-제목)\n' > docs/broken.md
fail 'missing heading' 'target.md'
rm docs/broken.md
printf '[숨김 문서](missing-hidden.md)\n' >> .claude/skills/example/SKILL.md
fail 'hidden agent document' 'missing-hidden.md'
cp .codex/skills/example/SKILL.md .claude/skills/example/SKILL.md
mv docs/target.md docs/moved.md
fail 'unchanged incoming links after target move' 'target.md'
mv docs/moved.md docs/target.md
printf '# 바뀐 제목\n' > docs/target.md
fail 'unchanged incoming links after heading change' 'target.md'
git checkout -- docs/target.md
rm assets/example.png
fail 'missing image' 'example.png'
git checkout -- assets/example.png
pass 'restored targets'

mkdir empty
cd empty
git init -q
pass 'repository without Markdown'
printf '# 삭제할 문서\n' > deleted.md
git add deleted.md
rm deleted.md
pass 'deleted last Markdown input'
