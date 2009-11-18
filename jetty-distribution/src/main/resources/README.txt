
JETTY
=====

The Jetty project is a 100% Java HTTP Server, HTTP Client
and Servlet Container. The core project is hosted by
the Eclipse Foundation at

  http://www.eclipse.org/jetty/

The jetty integrations with 3rd party modules are hosted
by the Codehaus at 

  http://jetty.codehaus.org


JETTY DISTRIBUTION
==================

This is the jetty-distribution module from Jetty @ eclipse 
project and is based on the Jetty modules from eclipse plus
dependencies that have been through the eclipse IP
process and conditioning.  

This distribution and its dependencies are provided under 
the terms and conditions of the Eclipse Foundation Software 
User Agreement unless otherwise specified. 

This distribution contains only the core functionality
of a servlet server and the HTTP client.

Some modules (eg annotations) are missing dependencies 
which may be discovered by using the command
  mvn dependency:tree 
within the source module and placing them in the
lib/ext directory.  Alternately we recommend the jetty-hightide
distribution for users that desire more third party integrations.


JETTY HIGHTIDE
==============

The Jetty-hightide distribution is available for
download via http://jetty.codehaus.org and contains
the core jetty modules, plus the 3rd party dependencies
and integrations needed to create a full featured
application server.


MAVEN
=====
All Jetty artifacts are available as maven dependencies
under the org.eclipse.jetty and org.mortbay.hightide group IDs

  http://repo1.maven.org/maven2/org/eclipse/jetty/
  http://repo2.maven.org/maven2/org/mortbay/jetty/


RUNNING JETTY
=============

The run directory is either the top-level of a distribution
or jetty-distribution/target/distribution directory when built from
source.

To run with the default options:

  java -jar start.jar

To run with specific configuration file(s)

  java -jar start.jar etc/jetty.xml

To see the available options

  java -jar start.jar --help

To run with JSP support (if available)

  java -jar start.jar OPTIONS=Server,jsp

To run with JMX support

  java -jar start.jar OPTIONS=Server,jmx etc/jetty-jmx.xml etc/jetty.xml

To run with JSP & JMX support

  java -jar start.jar OPTIONS=Server,jsp,jmx etc/jetty-jmx.xml etc/jetty.xml


