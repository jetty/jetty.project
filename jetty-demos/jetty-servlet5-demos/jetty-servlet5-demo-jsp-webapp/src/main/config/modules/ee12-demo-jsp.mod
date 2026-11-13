[description]
Demo Simple JSP Webapp

[environment]
ee12

[tags]
demo
webapp

[depends]
ee12-jsp
ee12-jstl
ee12-deploy

[files]
basehome:modules/demo.d/ee12-demo-jsp.properties|webapps/ee12-demo-jsp.properties
maven://org.eclipse.jetty.demos/jetty-servlet5-demo-jsp-webapp/${jetty.version}/war|webapps/ee12-demo-jsp.war
