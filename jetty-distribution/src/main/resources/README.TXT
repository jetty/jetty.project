
JETTY
=====
The Jetty project is a 100% Java HTTP Server, HTTP Client
and Servlet Container from the eclipse foundation

  http://www.eclipse.org/jetty/

Jetty is open source and is dual licensed using the Apache 2.0 and
Eclipse Public License 1.0.   You may choose either license when
distributing Jetty.


RUNNING JETTY
=============
The run directory is either the top-level of a binary release
or jetty-distribution/target/assembly-prep directory when built from
source.

To run with the default options:

  java -jar start.jar

To see the available options and the default arguments
provided by the start.ini file:

  java -jar start.jar --help


Many Jetty features can be enabled by using the --module command
For example:

  java -jar start.jar --module=https,deploy

Will enable HTTPS and its dependencies in the files start.ini
To list the know modules:

  java -jar start.jar --list-modules



JETTY BASE
==========

If the property jetty.base is defined on the command line, then the jetty start.jar
mechanism will look for start.ini, start.d, webapps and etc files relative to the
jetty.base and jetty.home directories

  java -jar start.jar jetty.base=/opt/myjettybase/



