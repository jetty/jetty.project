#!/bin/sh

[ $# -eq 1 ] || { echo "Usage - $0 jetty-dir" >&2 ; exit 1 ; }

cd $1
D=$(pwd)
N=$(basename $D)
D=$(dirname $D)
cd $D

 find $N -type f |\
 egrep -v /\\.svn |\
 egrep -v /target |\
 egrep -v /\\. |\
 egrep -v $N/start.jar |\
 egrep -v $N/lib |\
 egrep -v $N/logs |\
 egrep -v $N/webapp |\
 egrep -v $N/javadoc |\
 xargs zip $D/$N-src.zip $N/logs

 find $N -type f |\
 egrep -v /\\.svn |\
 egrep -v /target |\
 egrep -v /\\. |\
 egrep -v $N/logs |\
 xargs zip $D/$N.zip $N/logs
