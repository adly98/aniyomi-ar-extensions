#!/bin/bash
set -e

rsync -a --delete --exclude .git --exclude .gitignore ../main/repo/ .
git config --global user.email "111687237+adly98@users.noreply.github.com"
git config --global user.name "aniyomi-ar-bot[bot]"
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -S -m "Update extensions repo"
    git push

    # Purge cached index on jsDelivr
    curl https://purge.jsdelivr.net/gh/aniyomiorg/aniyomi-extensions@repo/index.min.json
else
    echo "No changes to commit"
fi
