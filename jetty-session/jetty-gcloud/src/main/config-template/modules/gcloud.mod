# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Controls GCloud API classpath.

[tags]
3rdparty
gcloud

[lib]
lib/gcloud/*.jar

[license]
GCloudDatastore is an open source project hosted on Github and released under the Apache 2.0 license.
https://github.com/GoogleCloudPlatform/gcloud-java
http://www.apache.org/licenses/LICENSE-2.0.html

[ini]
## Hide the gcloud libraries from deployed webapps
jetty.webapp.addServerClasses+=,${jetty.base.uri}/lib/gcloud/
