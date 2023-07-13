# Eclipse Jetty Canonical Repository

This is the canonical repository for the Eclipse Jetty project, feel free to fork and contribute now!

Submitting a patch or pull request?

Make sure you have an Eclipse Contributor Agreement (ECA) on file.

- [eclipse.org/legal/ecafaq](https://www.eclipse.org/legal/ecafaq.php)

## Project description

Eclipse Jetty is a lightweight, highly scalable, Java-based web server and Servlet engine.
Jetty's goal is to support web protocols (HTTP/1, HTTP/2, HTTP/3, WebSocket, etc.) in a high volume low latency way that provides maximum performance while retaining the ease of use and compatibility with years of Servlet development.
Jetty is a modern fully asynchronous web server that has a long history as a component oriented technology, and can be easily embedded into applications while still offering a solid traditional distribution for webapp deployment.

- [https://projects.eclipse.org/projects/rt.jetty](https://projects.eclipse.org/projects/rt.jetty)

## Webapp Example

```shell
$ mkdir base && cd base
$ java -jar $JETTY_HOME/start.jar --add-modules=http,ee10-deploy
$ cp ~/src/myproj/target/mywebapp.war webapps
$ java -jar $JETTY_HOME/start.jar 
```

## Multiple Versions Webapp Example

```shell
$ mkdir base && cd base
$ java -jar $JETTY_HOME/start.jar --add-modules=http,ee10-deploy,ee8-deploy
$ cp ~/src/myproj/target/mywebapp10.war webapps
$ cp ~/src/myproj/target/mywebapp8.war webapps
$ echo environment: ee8 > webapps/mywebapp8.properties
$ java -jar $JETTY_HOME/start.jar 
```

## Embedded Example

```java
Server server = new Server(port);
ServletContextHandler context = new ServletContextHandler(server, "/");
context.addServlet(MyServlet.class, "/*");
server.start();
```

## Documentation

Project documentation is available on the Eclipse Jetty website.

- [https://www.eclipse.org/jetty/documentation](https://www.eclipse.org/jetty/documentation)

# Building

[Apache Maven](https://maven.apache.org/) and [OpenJDK](https://adoptium.net/) requirements:

| Branch         | Maven Version | Minimum JDK | Recommended JDK                                              |
|----------------|---------------|-------------|--------------------------------------------------------------|
| `jetty-10.0.x` | Maven 3.8.6+  | OpenJDK 11  | OpenJDK 17 (for optional virtual threads and HTTP/3 support) |
| `jetty-11.0.x` | Maven 3.8.6+  | OpenJDK 11  | OpenJDK 17 (for optional virtual threads and HTTP/3 support) |
| `jetty-12.0.x` | Maven 3.8.6+  | OpenJDK 17  | OpenJDK 17                                                   |

## Full Build 

If you want to build Jetty and run all the tests, which may takes quite some time, use the following command:

``` shell
mvn clean install
```

Note: There are stress tests that on some hardware or operative system require to set the file descriptor limit to a value greater than 2048 to pass successfully (check your `ulimit -n` value).

Note: The tests are running in parallel using [Junit5 parallel execution](https://junit.org/junit5/docs/current/user-guide/#writing-tests-parallel-execution).
This is configurable using the following properties:

```
    # to enable/disable the parallel execution
    -Djunit.jupiter.execution.parallel.enabled=true/false
    # number of tests executed in parallel
    -Djunit.jupiter.execution.parallel.config.fixed.parallelism=2
```

If a test cannot be run in parallel because it accesses/modifies some `static` fields or for any other reasons, the test should be marked with the annotation:

```java
@Isolated("Access static field of Configurations")
```

## Fast Build

If you just need the Jetty module jars and the Jetty Home distribution, you can run a fast build that does not run tests and other checks with the following command:

``` shell
mvn -Pfast clean install
```

## Optional Build Tools 

* [`graphviz`](https://graphviz.org/) - used by asciidoctor in the jetty-documentation module to produce various graphs
* [`Docker`](https://www.docker.com/) - used to run some integration tests for testing third party integrations

## Build Artifacts

Once the build is complete, you can find the built Jetty Maven artifacts in your Maven local repository, along with the following locations of note:

| Branches       | Location                                                          | Description                                                                  |
|----------------|-------------------------------------------------------------------|------------------------------------------------------------------------------|
| all            | `jetty-home/target/jetty-home-<ver>.tar.gz`                       | The Jetty Home distribution                                                  |
| `jetty-10.0.x` | `jetty-runner/target/jetty-runner-<ver>.jar`                      | The Jetty Runner distribution                                                |
| `jetty-11.0.x` | `jetty-runner/target/jetty-runner-<ver>.jar`                      | The Jetty Runner distribution                                                |
| `jetty-12.0.x` | `jetty-ee10/jetty-ee10-runner/target/jetty-ee10-runner-<ver>.jar` | The Jetty Runner distribution for EE10/Servlet 6 (`jakarta.servlet`) webapps |
| `jetty-12.0.x` | `jetty-ee9/jetty-ee9-runner/target/jetty-ee9-runner-<ver>.jar`    | The Jetty Runner distribution for EE9/Servlet 5 (`jakarta.servlet`) webapps  |
| `jetty-12.0.x` | `jetty-ee8/jetty-ee8-runner/target/jetty-ee8-runner-<ver>.jar`    | The Jetty Runner distribution for EE8/Servlet 4 (`javax.servlet`) webapps    |

# Commercial Support

Expert advice and production support of Jetty are provided by [Webtide](https://webtide.com).
