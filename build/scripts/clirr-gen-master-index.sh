#!/bin/bash

SCRIPTSDIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

if [ ! -d "target/clirr" ] ; then
    mkdir -p "target/clirr"
fi

MIDX="target/clirr/index.html"

cat $SCRIPTSDIR/clirr-gen-master-index.output-head.html > $MIDX

find . -type f -name "clirr-report.xml" -print0 | while IFS= read -r -d $'\0' line; do
    shortname=$(echo $line | sed -e "s/^[^\/]\/*//;s/\/target.*//")
    xsltproc --stringparam reportpath "$shortname" \
        $SCRIPTSDIR/clirr-gen-master-index.output-html.xslt \
        $line >> $MIDX
    echo "$shortname"
done

cat $SCRIPTSDIR/clirr-gen-master-index.output-foot.html >> $MIDX

echo ""
echo "Master Clirr Index generated at at $MIDX"

