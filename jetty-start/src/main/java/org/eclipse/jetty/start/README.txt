Jetty start
-----------

The run directory is either the top-level of a distribution
or jetty-distribution/target/distribution directory when built from
source.

Jetty start.jar provides a cross platform replacement for startup scripts.
It makes use of executable JAR that builds the classpath and then executes
jetty.

To run with all the demo options:

  java -jar start.jar OPTIONS=All

To run with the default options:

  java -jar start.jar

The default options may be specified in the start.ini file, or if
that is not present, they are defined in the start.config file that
is within the start.jar.

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

Note that JSP requires the jasper jars to be within $JETTY/lib/jsp  These 
are currently not distributed with the eclipse release and must be
obtained from a jetty-hightide release from codehaus.

