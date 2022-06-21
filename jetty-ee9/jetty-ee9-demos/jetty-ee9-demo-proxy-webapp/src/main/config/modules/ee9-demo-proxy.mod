# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo Proxy Webapp

[environment]
ee9

[tags]
demo
webapp

[depends]
ee9-deploy

[files]
maven://org.eclipse.jetty.ee9.demos/ee9-demo-proxy-webapp/${jetty.version}/war|webapps-ee9/ee9-demo-proxy.war
