# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Download and deploy the Test Spec webapp demo.

[tags]
demo
webapp

[depends]
deploy
jdbc
jsp
annotations
test-realm
ext

[files]
basehome:modules/demo.d/test-spec.xml|webapps/test-spec.xml
maven://org.eclipse.jetty.tests/test-spec-webapp/${jetty.version}/war|webapps/test-spec.war
maven://org.eclipse.jetty.tests/test-mock-resources/${jetty.version}/jar|lib/ext/test-mock-resources-${jetty.version}.jar
