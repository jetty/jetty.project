#!/bin/bash

set

export PATH=$M3/bin:$PATH

mvn clean install -DskipTests

cd $MODULE

let count=0

while mvn $OPTS test -Dtest=$TESTNAME 2>&1 > target/lastbuild.log
do
    now=`date`
    echo "Test Run $count - $now" 
    let count=$count+1
done
