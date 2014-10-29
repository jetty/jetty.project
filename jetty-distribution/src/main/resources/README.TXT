
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
or jetty-distribution/target/distribution directory when built from
source.

To run with the default options:

  $ cd demo-base
  $ java -jar ../start.jar

To see the available options and the default arguments
provided by the start.ini file:

  $ java -jar /path/to/start.jar --help


Many Jetty features can be enabled by using the --module command
For example:

  $ cd mybase
  $ java -jar /path/to/start.jar --module=https,deploy

Will enable the https and deploy modules (and their transitive
dependencies) temporarily for this specific run of Jetty.

To see what modules are available

  $ java -jar /path/to/start.jar --list-modules



JETTY BASE
==========

The jetty.base property is a property that can be defined on the
command line (defaults to what your java 'user.dir' property points to)
Jetty's start.jar mechanism will configure your jetty instance from
the configuration present in this jetty.base directory.

Example setup:

# Create the base directory
  
  $ mkdir mybase
  $ cd mybase
  
# Initialize the base directory's start.ini and needed directories

  $ java -jar /path/to/jetty-dist/start.jar --add-to-start=http,deploy
  
# Run this base directory configuration
 
  $ java -jar /path/to/jetty-dist/start.jar

