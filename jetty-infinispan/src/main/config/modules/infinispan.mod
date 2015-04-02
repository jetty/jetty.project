#
# Jetty Infinispan module
#

[depend]
annotations
webapp


[lib]
lib/jetty-infinispan-${jetty.version}.jar
lib/infinispan/*.jar


[xml]
etc/jetty-infinispan.xml

#Infinispan is an open source project hosted on Github and released under the Apache 2.0 license.
#http://infinispan.org/
#http://www.apache.org/licenses/LICENSE-2.0.html

#You will need to copy the appropriate infinispan jars into lib/infinispan
