#!/bin/sh

git diff origin/jetty-12.1.x -- jetty-ee10 | sed -e 's/ee10/ee11/g' -e 's/EE10/EE11/g' | git apply
