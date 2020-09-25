ECLIPSE JETTY
=============
The Eclipse Jetty project is a 100% Java HTTP Server, HTTP Client
and Servlet Container from the eclipse foundation

  http://www.eclipse.org/jetty/

Jetty is open source and is dual licensed using the 
Eclipse Public License 2.0 (https://www.eclipse.org/legal/epl-2.0)
and the Apache License v2.0 (https://www.apache.org/licenses/LICENSE-2.0)
You may choose either license when distributing Jetty.

RUNNING JETTY 
=============
To run jetty you need to create a jetty base directory, that holds the
configuration separately from this jetty home distribution directory.
The following creates the jetty demonstration in a temporary base:

  $ export JETTY_HOME=$PWD
  $ mkdir /tmp/demo-base
  $ cd /tmp/demo-base
  $ java -jar $JETTY_HOME/start.jar --add-module=demo
  $ java -jar $JETTY_HOME/start.jar

To create a minimal configuration that will support WAR deployment:

  $ export JETTY_HOME=$PWD
  $ mkdir /tmp/jetty-base
  $ cd /tmp/jetty-base
  $ java -jar $JETTY_HOME/start.jar --add-module=http,deploy
  $ java -jar $JETTY_HOME/start.jar

Other modules can be added with the command:

  $ java -jar $JETTY_HOME/start.jar --add-module=<modulename>,...

To see what modules are available

  $ java -jar $JETTY_HOME/start.jar --list-modules

To see what the current configuration is

  $ java -jar $JETTY_HOME/start.jar --list-config

To see more start options

  $ java -jar $JETTY_HOME/start.jar --help

