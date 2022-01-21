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
ext
demo-realm
demo-mock-resources

[files]
basehome:modules/demo.d/demo-spec.xml|webapps/demo-spec.xml
maven://org.eclipse.jetty.demos/demo-spec-webapp/${jetty.version}/war|webapps/demo-spec.war
