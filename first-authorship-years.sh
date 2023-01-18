#!/bin/bash
# Use like this
#  $ find . -name "*.java" -exec ./first-authorship-years.sh {} \; | tee first-authorship.log
#

FILE="$1"
FIRST_YEAR=$(git log --follow --format=%ad --date format:%Y "$FILE" | tail -1)

echo $FIRST_YEAR \| $FILE

sed -i.orig -e "s/\(Copyright (c)\) [0-9\-]* \(Mort Bay Consulting Pty Ltd and others.\)/\1 $FIRST_YEAR \2/" $FILE
