# DO NOT EDIT THIS FILE - Use start modules correctly - See: https://eclipse.dev/jetty/documentation/

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
