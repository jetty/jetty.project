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
demo-ee9-realm
demo-ee9-mock-resources

[files]
basehome:modules/demo.d/demo-ee9-spec.xml|webapps-ee9/demo-ee9-spec.xml
maven://org.eclipse.jetty.demos/demo-ee9-spec-webapp/${jetty.version}/war|webapps-ee9/demo-ee9-spec.war
