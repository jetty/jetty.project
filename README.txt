
This is a source checkout of the Jetty webserver.

To build, use:

  mvn install

The jetty distribution will be built in

  jetty-distribution/target/distribution

The first build may take a long time as maven downloads all the
dependencies.

The tests do a lot of stress testing, and on some machines it is 
neccessary to set the file descriptor limit to greater than 2048
for the tests to all pass successfully.
