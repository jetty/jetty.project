[environment]
license=Apache-2.0
name=EE 10 Environment

[description]
Provides the Java EE 10 (Jakarta EE 10) environment.

[required]
server

[lib]
# No libs are shipped; they are downloaded when this module is added.

[download]
url="https://repo1.maven.org/maven2/org/eclipse/jetty/ee10/jetty-ee10-jetty-home/${jetty.version}/jetty-ee10-jetty-home-${jetty.version}.jar"
checksum="sha512:"

[download]
url="https://repo1.maven.org/maven2/org/eclipse/jetty/ee10/jetty-ee10-nested/${jetty.version}/jetty-ee10-nested-${jetty.version}.jar"
checksum="sha512:"
