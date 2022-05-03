#!/bin/bash

git log --date=iso \
  --pretty=format:"%h%x09%an%x09%ad%x09%s" \
  $@ > commits.tab.txt
