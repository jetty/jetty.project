[description]
Demo Simple JSP Webapp

[environment]
ee11

[tags]
demo
webapp

[depends]
ee11-jsp
ee11-jstl
ee11-deploy

[files]
basehome:modules/demo.d/ee11-demo-jsp.properties|webapps/ee11-demo-jsp.properties
maven://org.eclipse.jetty.demos/jetty-servlet5-demo-jsp-webapp/${jetty.version}/war|webapps/ee11-demo-jsp.war
