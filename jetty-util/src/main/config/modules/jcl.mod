[description]
Provides a Java Commons Logging implementation.  
To receive jetty logs the jetty-slf4j and slf4j-jcl must also be enabled.

[tags]
logging
jcl
verbose

[depends]

[provides]
jcl-api
jcl-impl

[files]
maven://commons-logging/commons-logging/${jcl.version}|lib/jcl/commons-logging-${jcl.version}.jar

[lib]
lib/jcl/commons-logging-${jcl.version}.jar

[license]
Log4j is released under the Apache 2.0 license.
http://www.apache.org/licenses/LICENSE-2.0.html

[ini]
jcl.version=1.1.3

[ini-template]
## After changing versions, run 'java -jar $JETTY_HOME/start.jar --create-files' 
#jcl.version=1.1.3
