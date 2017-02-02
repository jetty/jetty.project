[description]
Control GCloud API classpath

[tags]
3rdparty
gcloud

[lib]
lib/gcloud/*.jar

[license]
GCloudDatastore is an open source project hosted on Github and released under the Apache 2.0 license.
https://github.com/GoogleCloudPlatform/gcloud-java
http://www.apache.org/licenses/LICENSE-2.0.html

[ini-template]
## Hide the gcloud libraries from deployed webapps
jetty.webapp.addServerClasses+=,file:${jetty.base}/lib/gcloud/
