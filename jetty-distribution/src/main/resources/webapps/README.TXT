
This directory is scanned by the WebAppDeployer provider for web
applications to deploy using the following conventions:

+ A file matching the pattern *.war will be deployed as a standard web 
application with the context path set from the files basename.
So a file called "example.war" will be deployed with a context path of /example
and will be served at URLs like http://localhost:8080/example/). 
If the base name is root, then the context path is /. If example.war and example/
exist, then only the WAR is deployed (which may use the directory as an
unpack location).

+ A directory will be deployed as a standard web application if it 
contains a WEB-INF/ subdirectory, otherwise it will be deployed as 
context of static content. The context path will be the filename.
So a directory called "example/" will have a context path of /example
If the basename is "root", then the context path is /. 
If the directory name ends with ".d" it is ignored (and may be used 
to hold other configuration files).


+ An XML file will be deployed as a context whose configuration is 
defined by the XML. The context path must be set by the configuration 
itself. If "example.xml" and "example.war" exist, then only the XML 
is deployed (which may use the war in its configuration).

This directory is scanned for additions, removals and updates 
for hot deployment.

The jetty demo webapps are not served from this directory. Instead
the $JETTY_HOME/start.d/demo.ini file configures the 
$JETTY_HOME/webapps-demos directory as an alternate webapps deployment 
directory.

