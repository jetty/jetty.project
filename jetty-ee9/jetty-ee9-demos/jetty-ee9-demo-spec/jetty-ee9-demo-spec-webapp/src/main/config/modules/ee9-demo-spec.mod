# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Download and deploy the Test Spec webapp demo.

[environment]
ee9

[tags]
demo
webapp

[depends]
ee9-deploy
jdbc
jsp
ee9-annotations
ext
ee9-demo-realm
ee9-demo-mock-resources

[files]
basehome:modules/demo.d/ee9-demo-spec.xml|webapps/ee9-demo-spec.xml
basehome:modules/demo.d/ee9-demo-spec.properties|webapps/ee9-demo-spec.properties
maven://org.eclipse.jetty.ee9.demos/jetty-ee9-demo-spec-webapp/${jetty.version}/war|webapps/ee9-demo-spec.war
