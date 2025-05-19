#!/bin/sh

git diff origin/jetty-12.1.x -- jetty-ee11 | sed -e 's/ee11/ee10/g' -e 's/EE11/EE10/g' | git apply
