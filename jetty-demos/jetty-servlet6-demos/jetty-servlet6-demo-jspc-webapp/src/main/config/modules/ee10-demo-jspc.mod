[description]
Demo Simple Precompiled JSP Webapp

[environment]
ee10

[tags]
demo
webapp

[depends]
ee10-jsp
ee10-jstl
ee10-deploy

[files]
basehome:modules/demo.d/ee10-demo-jspc.properties|webapps/ee10-demo-jspc.properties
maven://org.eclipse.jetty.demos/jetty-servlet6-demo-jspc-webapp/${jetty.version}/war|webapps/ee10-demo-jspc.war
