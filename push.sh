#!/bin/bash
# Simple git push helper
# Usage: ./push.sh          # pushes current branch
#        ./push.sh --tags   # pushes current branch + tags

set -e

cd "$(dirname "$0")"

if [ "$1" = "--tags" ] || [ "$1" = "-t" ]; then
    echo "Pushing branch and tags..."
    git push origin master --tags
else
    echo "Pushing branch..."
    git push origin master
fi