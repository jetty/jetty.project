Eclipse Jetty Canonical Repository
==================================

This is the canonical repository for the Jetty project, feel free to fork and contribute now!

Submitting a patch or pull request?

Make sure you have an Eclipse Contributor Agreement (ECA) on file.

- [eclipse.org/legal/ecafaq](https://www.eclipse.org/legal/ecafaq.php)

Project description
-------------------

Jetty is a lightweight highly scalable java based web server and servlet engine.
Our goal is to support web protocols like HTTP, HTTP/2 and WebSocket in a high volume low latency way that provides maximum performance while retaining the ease of use and compatibility with years of servlet development.
Jetty is a modern fully async web server that has a long history as a component oriented technology easily embedded into applications while still offering a solid traditional distribution for webapp deployment.

- [https://projects.eclipse.org/projects/rt.jetty](https://projects.eclipse.org/projects/rt.jetty)

Webapp Example
--------------
```shell
$ mkdir base && cd base
$ java -jar $JETTY_HOME/start.jar --add-modules=http,deploy
$ cp ~/src/myproj/target/mywebapp.war webapps
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
-------------

Project documentation is available on the Jetty Eclipse website.

- [https://jetty.org/docs/](https://jetty.org/docs/)

Building
========

[Apache Maven 3.8.0](https://maven.apache.org/) and [OpenJDK](https://adoptium.net/) requirements:

Branch         | Maven Version | Minimum JDK | Recommended JDK
---------------|---------------|-------------| ---------------
`jetty-10.0.x` | Maven 3.8.6+  | OpenJDK 11  | OpenJDK 17 (for optional loom and http/3 support)
`jetty-11.0.x` | Maven 3.8.6+  | OpenJDK 11  | OpenJDK 17 (for optional loom and http/3 support)
`jetty-12.0.x` | Maven 3.8.6+  | OpenJDK 17  | OpenJDK 17

Full Build with All Tests:

``` shell
mvn clean install
```

Fast Build if you need jars and distribution (not running tests, checkstyle, enforcer, license check):

``` shell
mvn -Pfast clean install
```
Optional build tools: 

* [`graphviz`](https://graphviz.org/) - used by asciidoctor in the jetty-documentation build to produce various graphs
* [`Docker`](https://www.docker.com/) - used to run some integration tests for testing third party integrations

Once the build is complete, you can find the built Jetty Maven artifacts in your Maven local repository.
Along with the following locations of note:

Branches       | Location                                                          | Description
---------------|-------------------------------------------------------------------|---------
all            | `jetty-home/target/jetty-home-<ver>.tar.gz`                       | The Jetty Home standalone tarball
`jetty-10.0.x` | `jetty-runner/target/jetty-runner-<ver>.jar`                      | The Jetty Runner uber jar
`jetty-11.0.x` | `jetty-runner/target/jetty-runner-<ver>.jar`                      | The Jetty Runner uber jar
`jetty-12.0.x` | `jetty-ee10/jetty-ee10-runner/target/jetty-ee10-runner-<ver>.jar` | The Jetty Runner uber jar for ee10/Servlet 6 (jakarta.servlet) webapps
`jetty-12.0.x` | `jetty-ee9/jetty-ee9-runner/target/jetty-ee9-runner-<ver>.jar`    | The Jetty Runner uber jar for ee9/Servlet 5 (jakarta.servlet) webapps
`jetty-12.0.x` | `jetty-ee8/jetty-ee8-runner/target/jetty-ee8-runner-<ver>.jar`    | The Jetty Runner uber jar for ee8/Servlet 4 (javax.servlet) webapps

Note: The build tests do a lot of stress testing, and on some machines it is necessary to set the 
file descriptor limit to greater than 2048 for the tests to all pass successfully (check your `ulimit -n` value).

Professional Services
---------------------

Expert advice and production support are available through [Webtide.com](https://webtide.com).
