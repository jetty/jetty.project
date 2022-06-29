# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Download and deploy the Test Spec webapp demo.

[environment]
ee10

[tags]
demo
webapp

[depends]
deploy
jdbc
ee10-jsp
ee10-annotations
ext
ee10-demo-realm
ee10-demo-mock-resources

[files]
basehome:modules/demo.d/ee10-demo-spec.xml|webapps/ee10-demo-spec.xml
maven://org.eclipse.jetty.ee10.demos/jetty-ee10-demo-spec-webapp/${jetty.version}/war|webapps/ee10-demo-spec.war
