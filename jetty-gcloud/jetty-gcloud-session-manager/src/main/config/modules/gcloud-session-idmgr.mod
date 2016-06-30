#
# Jetty GCloudDatastore SessionIdManager module
#

[depend]
annotations
webapp

[files]
maven://com.google.cloud/gcloud-java-datastore/0.2.3|lib/gcloud/gcloud-java-datastore-0.2.3.jar
maven://com.google.cloud/gcloud-java-core/0.2.3|lib/gcloud/gcloud-java-core-0.2.3.jar
maven://com.google.auth/google-auth-library-credentials/0.3.1|lib/gcloud/google-auth-library-credentials-0.3.1.jar
maven://com.google.auth/google-auth-library-oauth2-http/0.3.1|lib/gcloud/google-auth-library-oauth2-http-0.3.1.jar
maven://com.google.http-client/google-http-client-jackson2/1.19.0|lib/gcloud/google-http-client-jackson2-1.19.0.jar
maven://com.fasterxml.jackson.core/jackson-core/2.1.3|lib/gcloud/jackson-core-2.1.3.jar
maven://com.google.http-client/google-http-client/1.21.0|lib/gcloud/google-http-client-1.21.0.jar
maven://com.google.code.findbugs/jsr305/1.3.9|lib/gcloud/jsr305-1.3.9.jar
maven://org.apache.httpcomponents/httpclient/4.0.1|lib/gcloud/httpclient-4.0.1.jar
maven://org.apache.httpcomponents/httpcore/4.0.1|lib/gcloud/httpcore-4.0.1.jar
maven://commons-logging/commons-logging/1.1.1|lib/gcloud/commons-logging-1.1.1.jar
maven://commons-codec/commons-codec/1.3|lib/gcloud/commons-codec-1.3.jar
maven://com.google.oauth-client/google-oauth-client/1.21.0|lib/gcloud/google-oauth-client-1.21.0.jar
maven://com.google.guava/guava/19.0|lib/gcloud/guava-19.0.jar
maven://com.google.api-client/google-api-client-appengine/1.21.0|lib/gcloud/google-api-client-appengine-1.21.0.jar
maven://com.google.oauth-client/google-oauth-client-appengine/1.21.0|lib/gcloud/google-oauth-client-appengine-1.21.0.jar
maven://com.google.oauth-client/google-oauth-client-servlet/1.21.0|lib/gcloud/google-oauth-client-servlet-1.21.0.jar
maven://com.google.http-client/google-http-client-jdo/1.21.0|lib/gcloud/google-http-client-jdo-1.21.0.jar
maven://com.google.api-client/google-api-client-servlet/1.21.0|lib/gcloud/google-api-client-servlet-1.21.0.jar
maven://javax.jdo/jdo2-api/2.3-eb|lib/gcloud/jdo2-api-2.3-eb.jar
maven://javax.transaction/transaction-api/1.1|lib/gcloud/transaction-api-1.1.jar
maven://com.google.http-client/google-http-client-appengine/1.21.0|lib/gcloud/google-http-client-appengine-1.21.0.jar
maven://com.google.http-client/google-http-client-jackson/1.21.0|lib/gcloud/google-http-client-jackson-1.21.0.jar
maven://org.codehaus.jackson/jackson-core-asl/1.9.11|lib/gcloud/jackson-core-asl-1.9.11.jar
maven://joda-time/joda-time/2.9.2|lib/gcloud/joda-time-2.9.2.jar
maven://org.json/json/20151123|lib/gcloud/json-20151123.jar
maven://com.google.cloud.datastore/datastore-v1beta3-protos/1.0.0-beta|lib/gcloud/datastore-v1beta3-protos-1.0.0-beta.jar
maven://com.google.protobuf/protobuf-java/3.0.0-beta-1|lib/gcloud/protobuf-java-3.0.0-beta-1.jar
maven://com.google.cloud.datastore/datastore-v1beta3-proto-client/1.0.0-beta.2|lib/gcloud/datastore-v1beta3-proto-client-1.0.0-beta.2.jar
maven://com.google.http-client/google-http-client-protobuf/1.20.0|lib/gcloud/google-http-client-protobuf-1.20.0.jar
maven://com.google.api-client/google-api-client/1.20.0|lib/gcloud/google-api-client-1.20.0.jar
maven://com.google.guava/guava-jdk5/13.0|lib/gcloud/guava-jdk5-13.0.jar



[lib]
lib/jetty-gcloud-session-manager-${jetty.version}.jar
lib/gcloud/*.jar

[xml]
etc/jetty-gcloud-session-idmgr.xml

[license]
GCloudDatastore is an open source project hosted on Github and released under the Apache 2.0 license.
https://github.com/GoogleCloudPlatform/gcloud-java
http://www.apache.org/licenses/LICENSE-2.0.html

[ini-template]
## Unique identifier to force the workername for this node in the cluster
## If not set, will default to the string "node" plus the Env variable $GAE_MODULE_INSTANCE
# jetty.gcloudSession.workerName=node1



