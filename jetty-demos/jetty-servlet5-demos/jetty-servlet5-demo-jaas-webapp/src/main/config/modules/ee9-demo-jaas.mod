# DO NOT EDIT THIS FILE - See: https://eclipse.dev/jetty/documentation/

[description]
Demo EE9 JAAS webapp

[environment]
ee9

[tags]
demo
webapp

[depends]
demo-jaas
ee9-deploy
jaas
jdbc
ee9-jsp
ee9-annotations
ext

[files]
basehome:modules/demo.d/ee9-demo-jaas.xml|webapps/ee9-demo-jaas.xml
basehome:modules/demo.d/ee9-demo-jaas.properties|webapps/ee9-demo-jaas.properties
maven://org.eclipse.jetty.demos/jetty-servlet5-demo-jaas-webapp/${jetty.version}/war|webapps/ee9-demo-jaas.war
