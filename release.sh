#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

die() { echo "$*" >&2; exit 1; }
set_version() { sed -i.bak "s/^version=.*/version=$1/" gradle.properties && rm gradle.properties.bak; }

current=$(sed -n 's/^version=//p' gradle.properties)
release=${1:-${current%-SNAPSHOT}}

[[ $release =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "Invalid version: $release"
next="${release%.*}.$((${release##*.} + 1))-SNAPSHOT"

[[ $(git branch --show-current) == main ]] || die "Not on main"
[[ -z $(git status --porcelain) ]] || die "Working tree not clean"
git fetch --quiet --tags origin main
git merge-base --is-ancestor origin/main HEAD || die "main is behind origin/main"
git rev-parse --quiet --verify "refs/tags/v$release" >/dev/null && die "Tag v$release already exists"

set_version "$release"
sed -i.bak 's/\(kotlin-react-compiler") version "\)[^"]*/\1'"$release"'/' README.md && rm README.md.bak
git diff --quiet || git commit --quiet -am "Release $release"
git tag "v$release"

set_version "$next"
git commit --quiet -am "Set next development version $next"

git push --atomic origin main "v$release"
echo "Released v$release, main is now $next"
