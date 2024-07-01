[description]
Demo Simple Webapp

[environment]
ee10

[tags]
demo
webapp

[depends]
ee10-deploy

[files]
basehome:modules/demo.d/ee10-demo-simple.properties|webapps/ee10-demo-simple.properties
maven://org.eclipse.jetty.demos/jetty-servlet5-demo-simple-webapp/${jetty.version}/war|webapps/ee10-demo-simple.war
