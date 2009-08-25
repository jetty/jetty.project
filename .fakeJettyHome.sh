#!/bin/sh

# This script creates a fake jetty home in the top level project of jetty.
# run this after the project has built.

rm -fr contexts etc lib logs start.jar webapps
mkdir contexts etc lib logs webapps

cd lib
find ../jetty-* -name target | egrep -v -e 'jetty-aggregate' -e 'jetty-start' | while read T
do
    find $T -name '*.jar' -maxdepth 1 | egrep -v -e '-javadoc' -e '-sources' -e '-config' -e '-tests' | while read J
    do
	ln -s $J .
    done
done
ln -s $HOME/.m2/repository/org/mortbay/jetty/servlet-api/2.5-20081211/servlet-api-2.5-20081211.jar servlet-api-2.5.jar
cd ..

find jetty-start/target -maxdepth 1 -name '*.jar' | egrep -v -e '-javadoc' -e '-sources' -e '-config' -e '-tests' | while read J
do
    ln -s $J start.jar
done

cd etc
find ../jetty-* -name etc | egrep 'src/main/config/etc' | egrep -v -e 'jetty-aggregate' -e 'jetty-start' | while read E
do
    find $E -maxdepth 1 -type f | while read F
    do
        ln -s $F .
    done
done
cd ..

cd contexts
find ../*-* -name contexts | egrep 'src/main/config/contexts' | while read C
do
    ls $C | egrep -v .svn | while read F
    do
        ln -s $C/$F .
    done
done
cd ..

cd webapps
ln -s ../test-jetty-webapp/target/*.war test.war
cd ..

ln -s jetty-distribution/src/main/resources/start.ini .
