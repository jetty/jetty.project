
JETTY
=====

The Jetty project is a 100% Java HTTP Server, HTTP Client
and Servlet Container.


The Jetty @ eclipse project is based on the Jetty project at codehaus

  http://jetty.codehaus.org

Ongoing development is now at the eclipse foundation

  http://www.eclipse.org/jetty/


Jetty @ eclipse is open source and is dual licensed using the apache 2.0 and
eclipse public license 1.0.   You may choose either license when distributing
jetty.



BUILDING JETTY
==============

Jetty uses maven 2 as its build system.  Maven will fetch
the dependancies, build the server and assemble a runnable
version:

  mvn install



RUNNING JETTY
=============

The run directory is either the top-level of a binary release
or jetty-distribution/target/assembly-prep directory when built from
source.

To run with the default options:

  java -jar start.jar

To run with specific configuration file(s)

  java -jar start.jar etc/jetty.xml

To see the available options

  java -jar start.jar --help

To run with JSP support

  java -jar start.jar OPTIONS=Server,jsp

To run with JMX support

  java -jar start.jar OPTIONS=Server,jmx etc/jetty-jmx.xml etc/jetty.xml


