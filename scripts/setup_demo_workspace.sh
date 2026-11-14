#!/usr/bin/env bash
# setup_demo_workspace.sh — the small project the coding agents work on in the final demo
# (W16AAPPLE-52).
#
# Usage:  ./scripts/setup_demo_workspace.sh [path]        create it (default: ~/oochat-demo)
#         ./scripts/setup_demo_workspace.sh --reset [path] put it back as it started, after a run
# Exit:   0 = ready, 1 = path is in the way, 2 = bad usage or a missing tool.
#
# The workspace is a tiny to-do list in Python, in its own git repository, with one bug and
# one test that fails because of it. That gives Claude Code or Codex a real, quick task
# ("fix the failing test") and a longer one to stop part way ("add tests for every function,
# fix any bugs, run them"), without letting a live demo touch anything that matters.
#
# --reset only ever runs on a workspace this script made: it checks for the first commit's
# message before discarding anything.
#
# Portable to bash 3.2 (macOS default), like audit_repo.sh. Runs under Git Bash on Windows.

set -euo pipefail

FIRST_COMMIT="Demo workspace: a small to-do list with one failing test"

reset=false
if [ "${1:-}" = "--reset" ]; then
    reset=true
    shift
fi
case "${1:-}" in
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    -*) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
esac
DIR="${1:-$HOME/oochat-demo}"

command -v git >/dev/null 2>&1 || { echo "git is required" >&2; exit 2; }

if $reset; then
    if [ "$(git -C "$DIR" log --reverse --format=%s 2>/dev/null | head -n 1)" != "$FIRST_COMMIT" ]; then
        echo "$DIR is not a demo workspace made by this script; not resetting it." >&2
        exit 1
    fi
    first=$(git -C "$DIR" rev-list --max-parents=0 HEAD)
    git -C "$DIR" reset -q --hard "$first"
    git -C "$DIR" clean -q -fdx
    echo "Reset $DIR to its first commit; the bug and the failing test are back."
    exit 0
fi

if [ -e "$DIR" ]; then
    echo "$DIR already exists. Use --reset to put a demo workspace back as it started," >&2
    echo "or pass another path." >&2
    exit 1
fi

mkdir -p "$DIR/tasks" "$DIR/tests"

: > "$DIR/tasks/__init__.py"

cat > "$DIR/tasks/store.py" <<'EOF'
"""A tiny to-do list, kept in a JSON file. Small on purpose: it is the demo's workspace."""
import json
from pathlib import Path

DEFAULT_FILE = Path("tasks.json")


def load(path=DEFAULT_FILE):
    if not Path(path).exists():
        return []
    return json.loads(Path(path).read_text(encoding="utf-8"))


def save(tasks, path=DEFAULT_FILE):
    Path(path).write_text(json.dumps(tasks, indent=2), encoding="utf-8")


def add(tasks, title):
    tasks.append({"id": len(tasks) + 1, "title": title, "done": False})
    return tasks


def complete(tasks, task_id):
    for task in tasks:
        if task["id"] == task_id:
            task["done"] = True
    return tasks


def pending(tasks):
    # Bug on purpose, for the demo: this returns the finished tasks.
    return [task for task in tasks if task["done"]]
EOF

cat > "$DIR/tests/test_store.py" <<'EOF'
from tasks import store


def test_add_numbers_tasks_from_one():
    tasks = store.add([], "Buy milk")
    assert tasks == [{"id": 1, "title": "Buy milk", "done": False}]


def test_pending_lists_only_unfinished_tasks():
    tasks = store.add(store.add([], "Buy milk"), "Call Sam")
    store.complete(tasks, 1)
    assert [task["title"] for task in store.pending(tasks)] == ["Call Sam"]
EOF

cat > "$DIR/README.md" <<'EOF'
# oochat demo workspace

A tiny to-do list for the final demo. Coding agents work here, nowhere else.

Reset it after a run with `scripts/setup_demo_workspace.sh --reset` from the oochat repo.
EOF

printf '__pycache__/\n*.pyc\ntasks.json\n.pytest_cache/\n' > "$DIR/.gitignore"

git -C "$DIR" init -q
git -C "$DIR" add -A
# A fixed author, so the workspace is the same on every machine and needs no git identity.
git -C "$DIR" -c user.name="oochat demo" -c user.email="demo@example.invalid" \
    commit -q -m "$FIRST_COMMIT"

cat <<EOF
Demo workspace ready at $DIR

  Tests:   cd "$DIR" && python -m pytest -q     (one fails on purpose; needs: pip install pytest)
  Prompts: "Use Claude Code to fix the failing test in $DIR"
           "Use Claude Code to add tests for every function in tasks/store.py, fix any bugs,
            and run the tests"   (long enough to stop part way)
  Reset:   $0 --reset "$DIR"
EOF
