[description]
Demo Simple Precompiled JSP Webapp

[environment]
ee8

[tags]
demo
webapp

[depends]
ee8-jsp
ee8-jstl
ee8-deploy

[files]
basehome:modules/demo.d/ee8-demo-jspc.properties|webapps/ee8-demo-jspc.properties
maven://org.eclipse.jetty.demos/jetty-servlet4-demo-jspc-webapp/${jetty.version}/war|webapps/ee8-demo-jspc.war
