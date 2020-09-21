# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo Proxy Webapp

[tags]
demo
webapp

[depends]
deploy

[files]
maven://org.eclipse.jetty.tests/test-proxy-webapp/${jetty.version}/war|webapps/demo-proxy.war
