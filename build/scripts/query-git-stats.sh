#!/bin/bash

EXCLUDED_FILES="/\.xml$/d;/\.txt$/d;/package-info\.java/d;/\.yml$/d;/\.md$/d;/\.mod$/d"
EXCLUDED_PATHS="/jetty-ant\//d;/tests\//d;/examples\//d;/\/src\/test\//d"

FILTEREDLOG=git-filtered.log

git log \
  --after '2015-12-01 00:00' \
  --until '2016-03-31 23:59' \
  --oneline > $FILTEREDLOG

UNIQCOMMITS=$(cat $FILTEREDLOG | wc -l)

git log \
  --after '2015-12-01 00:00' \
  --until '2016-03-31 23:59' \
  --numstat --format= | sed \
    -e "$EXCLUDED_FILES" \
    -e "$EXCLUDED_PATHS" \
    | sort --key=3 > $FILTEREDLOG

UNIQFILES=$(cat $FILTEREDLOG | cut -f 3- | uniq | wc -l)

# Show output
echo "$UNIQCOMMITS unique commits"
echo "$UNIQFILES unique files"
cat $FILTEREDLOG | awk '{total = total + $1}END{print total " lines added"}'
cat $FILTEREDLOG | awk '{total = total + $2}END{print total " lines removed"}'

rm $FILTEREDLOG

