#!/bin/bash

set

mvn clean install -DskipTests

cd $MODULE

let count=0

while mvn test $TESTNAME 2>&1 > target/lastbuild.log
do
    now=`date`
    echo "Test Run $count - $now" >> target/looptest.log
    let count=$count+1
done

