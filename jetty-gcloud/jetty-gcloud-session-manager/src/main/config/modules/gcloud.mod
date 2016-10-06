[description]
Control GCloud API classpath

[Tags]
3rdparty
gcloud

[files]
basehome:modules/gcloud/gcloud.xml|etc/gcloud.xml

[lib]
lib/gcloud/*.jar

[xml]
etc/gcloud.xml

[license]
GCloudDatastore is an open source project hosted on Github and released under the Apache 2.0 license.
https://github.com/GoogleCloudPlatform/gcloud-java
http://www.apache.org/licenses/LICENSE-2.0.html

[ini-template]
## Configure the jars and packages exposed or hidden from webapps by comma separated
## list of classnames, package names or file URIs (See ClasspathPattern)
## Eg. to hide all gcloud dependencies other than the com.google.guava package:
## Default is all jars in lib/gcloud are hidden 
# gcloud.addServerClasses=file:${jetty.base}/lib/gcloud,-com.google.guava.
