#!/bin/bash


# look for JETTY_HOME
if [ -z "$JETTY_HOME" ] 
then
  JETTY_HOME_1=`dirname "$0"`
  JETTY_HOME_1=`dirname "$JETTY_HOME_1"`
  JETTY_HOME=${JETTY_HOME_1} 
fi

cd $JETTY_HOME
exec /usr/bin/java -Djetty.port=8088 -jar start.jar etc/jetty.xml etc/jetty-xinetd.xml

