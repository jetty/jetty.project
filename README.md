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

Documentation
-------------

Project documentation is available on the Jetty Eclipse website.

- [https://www.eclipse.org/jetty/documentation](https://www.eclipse.org/jetty/documentation)

Building
========

To build, use:

``` shell
  mvn clean install
```

The Jetty distribution will be built in `jetty-distribution/target/distribution`.

The first build may take a longer than expected as Maven downloads all the dependencies.

The build tests do a lot of stress testing, and on some machines it is necessary to set the file descriptor limit to greater than 2048 for the tests to all pass successfully.

It is possible to bypass tests by building with `mvn clean install -DskipTests`.

Professional Services
---------------------

Expert advice and production support are available through [Webtide.com](https://webtide.com).
