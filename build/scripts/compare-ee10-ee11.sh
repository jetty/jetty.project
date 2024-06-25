#!/bin/bash
find jetty-ee10 -name '*.java' | \
while read F 
do 
  G=$(echo $F | sed -e 's/ee10/ee11/g' -e 's/EE10/EE11/g') 
  if [ -e $G ]
  then
    sed -e 's/ee10/ee11/g' -e 's/EE10/EE11/g' $F | diff -q - $G > /dev/null
    if [ $? = 1 ]
    then
      echo diff $F $G
      sed -e 's/ee10/ee11/g' -e 's/EE10/EE11/g' $F | diff - $G 
    fi
  fi
done
