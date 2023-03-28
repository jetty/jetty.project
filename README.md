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

- [https://www.eclipse.org/jetty/documentation](https://www.eclipse.org/jetty/documentation)

Building
========

JDK to use:
- `jetty-10.0.x` and `jetty-11.0.x` branches you wil need jdk11 
- `jetty-12.0.x` you wil need jdk17

To build, use [Apache Maven 3.8.0](https://maven.apache.org/) (or better):

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

Eclipse Jetty will be built in `jetty-home/target/jetty-home`.

The first build may take a longer than expected as Maven downloads all the dependencies.

The build tests do a lot of stress testing, and on some machines it is necessary to set the file descriptor limit to greater than 2048 for the tests to all pass successfully.

It is possible to bypass tests by building with `mvn clean install -DskipTests`.

Professional Services
---------------------

Expert advice and production support are available through [Webtide.com](https://webtide.com).
