RUNNING JETTY

Assuming unix commands the general form of running jetty is:

 $ JETTY_HOME=/path/to/jetty-home
 $ cd /path/to/jetty-base
 $ java -jar $JETTY_HOME/start.jar

To see all the options to the start command:
 $ java -jar $JETTY_HOME/start.jar --help


RUNNING THE DEMO_BASE

To see the configuration of the included demo-base
 $ cd demo-base
 $ java -jar $JETTY_HOME/start.jar --list-config

To run the demo (from the demo-base directory):
 $ java -jar $JETTY_HOME/start.jar


RUNNING A WAR

This distribution contains a jetty-base directory with a minimal configuration.
To enable http and webapp deployment for this base
 $ JETTY_HOME=$PWD/jetty-home
 $ cd jetty-base
 $ java -jar $JETTY_HOME/start.jar --add-to-start=http,deploy
 $ cp /path/to/mywebapp.war webapps

To see what other modules can be configured:
 $ java -jar $JETTY_HOME/start.jar --list-modules

This war in this base can then be run with
 $ java -jar $JETTY_HOME/start.jar


CREATING A NEW JETTY BASE

A new Jetty base can be created anywhere on the file system and with any name:
 $ mkdir /path/to/my-jetty-base
 $ cd /path/to/my-jetty-base
 $ java -jar $JETTY_HOME/start.jar --create-startd --add-to-start=server,http,deploy
 $ cp /path/to/mywebapp.war webapps

This base can then be run with

 $ java -jar $JETTY_HOME/start.jar
