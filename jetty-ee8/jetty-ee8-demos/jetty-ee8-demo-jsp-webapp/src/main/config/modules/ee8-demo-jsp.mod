[description]
Demo Simple JSP Webapp

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
basehome:modules/demo.d/ee8-demo-jsp.properties|webapps/ee8-demo-jsp.properties
maven://org.eclipse.jetty.ee8.demos/jetty-ee8-demo-jsp-webapp/${jetty.version}/war|webapps/ee8-demo-jsp.war
