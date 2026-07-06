[description]
Add Apache Jasper JSP engine to the server.

[tags]
jsp

[depends]
webapp

[files]
maven://org.eclipse.jetty/apache-jsp/${jetty.version}/apache-jsp-${jetty.version}.jar
maven://org.eclipse.jetty/apache-jstl/${jetty.version}/apache-jstl-${jetty.version}.jar
maven://org.mortbay.jasper/apache-jsp/9.0.69/apache-jsp-9.0.69.jar
maven://org.apache.taglibs:taglibs-standard-impl:1.2.5

[ini]
jetty.ee.jsp.initialize=org.eclipse.jetty.ee.jsp.JspInitializer

[license]
Apache License 2.0

[version]
9.0.69
