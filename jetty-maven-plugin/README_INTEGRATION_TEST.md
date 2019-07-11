Running Maven Integration tests
=====================
The project contains a set of Maven Integration test projects.
They are running using the Maven Invoker plugin which starts an external Maven build to run the project and some post build check.
More details [http://maven.apache.org/plugins/maven-invoker-plugin/].

Integration tests location
--------------------
Test projects are located within the folder: src/it

Running the tests
--------------------
As they can be long to run, the tests do not run per default. So to run them you must activate a profile using the command line argument: ```-Prun-its``` 

Running single test
--------------------
You can run single or set of test as well using the command line argument: ```-Dinvoker.test=it-parent-pom,jetty-run-mojo-it,jetty-run-war*-it,!jetty-run-distro*```
The parameter supports pattern and exclusion with !

Due to [files  filtering](http://maven.apache.org/plugins/maven-invoker-plugin/examples/filtering.html), ```it-parent-pom``` must be included - otherwise tests will fail during execution. 

Running Logs
--------------------
The output of each Maven build will be located in /target/it/${project-name}/build.log

The jetty log output for those goals that fork a new process (currently "distro" and "run-forked") can be found in /target/it/${project-name}/jetty-simple-webapp/target/jetty.out.
