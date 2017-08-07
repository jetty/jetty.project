
This directory is scanned by the WebAppDeployer provider for web
applications to deploy using the following conventions:

+ A directory called example/ will be deployed as a standard web
application if it contains a WEB-INF/ subdirectory, otherwise it will be
deployed as context of static content. The context path will be /example
(eg http://localhost:8080/example/) unless the base name is "root" (not 
case sensitive), in which case the context path is /. If the directory name 
ends with ".d" it is ignored (but may still be used by explicit configuration).

+ A file called example.war will be deployed as a standard web application
with the context path /example (eg http://localhost:8080/example/). If the
base name is root, then the context path is /. If example.war and example/
exist, then only the WAR is deployed (which may use the directory as an
unpack location).

+ An XML file like example.xml will be deployed as a context whose
configuration is defined by the XML. The context path must be set by
the configuration itself. If example.xml and example.war exist, then
only the XML is deployed (which may use the war in its configuration).

This directory is scanned for additions, removals and updates 
for hot deployment.

To configure the deployment mechanism, edit the files:
   start.d/500-deploy.ini
   etc/jetty-deploy.ini

To disable the auto deploy mechanism use the command:

   java -jar start.jar --disable=deploy

