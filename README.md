Eclipse Jetty
=============

Eclipse Jetty is a lightweight, highly scalable, Java-based web server and Servlet engine.
Jetty's goal is to support web protocols (HTTP/1, HTTP/2, HTTP/3, WebSocket, etc.) in a high volume low latency way that provides maximum performance while retaining the ease of use and compatibility with years of Servlet development.
Jetty is a modern fully asynchronous web server that has a long history as a component oriented technology, and can be easily embedded into applications while still offering a solid traditional distribution for webapp deployment.

- [https://projects.eclipse.org/projects/rt.jetty](https://projects.eclipse.org/projects/rt.jetty)

Webapp Example
--------------

```shell
$ mkdir base && cd base
$ java -jar $JETTY_HOME/start.jar --add-modules=http,ee10-deploy
$ cp ~/src/myproj/target/mywebapp.war webapps
$ java -jar $JETTY_HOME/start.jar 
```

Multiple Versions Webapp Example
--------------------------------

```shell
$ mkdir base && cd base
$ java -jar $JETTY_HOME/start.jar --add-modules=http,ee10-deploy,ee8-deploy
$ cp ~/src/myproj/target/mywebapp10.war webapps
$ cp ~/src/myproj/target/mywebapp8.war webapps
$ echo environment: ee8 > webapps/mywebapp8.properties
$ java -jar $JETTY_HOME/start.jar 
```

Embedded Example
----------------

```java
Server server = new Server(port);
ServletContextHandler context = new ServletContextHandler(server, "/");
context.addServlet(MyServlet.class, "/*");
server.start();
```

Documentation
=============

[Jetty's documentation](https://eclipse.dev/jetty/documentation) is available on the Eclipse Jetty website.

The documentation is divided into three guides, based on use case:

* The [Operations Guide](https://eclipse.dev/jetty/documentation/jetty-12/operations-guide/index.html) targets sysops, devops, and developers who want to install Eclipse Jetty as a standalone server to deploy web applications.

* The [Programming Guide](https://eclipse.dev/jetty/documentation/jetty-11/programming-guide/index.html) targets developers who want to use the Eclipse Jetty libraries in their applications, and advanced sysops/devops that want to customize the deployment of web applications.

* The [Contribution Guide](https://eclipse.dev/jetty/documentation/jetty-11/contribution-guide/index.html) targets developers that wish to contribute to the Jetty Project with code patches or documentation improvements.


Commercial Support
==================
Expert advice and production support of Jetty are provided by [Webtide](https://webtide.com).
