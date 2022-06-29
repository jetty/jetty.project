# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo Proxy Webapp

[environment]
ee10

[tags]
demo
webapp

[depends]
ee10-deploy

[files]
maven://org.eclipse.jetty.ee10.demos/ee10-demo-proxy-webapp/${jetty.version}/war|webapps/ee10-demo-proxy.war
