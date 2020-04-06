Unit Tests with OSGi
--------------------

The unit tests use PaxExam https://ops4j1.jira.com/wiki/spaces/PAXEXAM4/overview
to fork a jvm to start an OSGi container (currently eclipse) and deploy the jetty
jars as osgi bundles, along with the jetty-osgi infrastructure (like jetty-osgi-boot).

To run all the tests:
   mvn test

To run a particular test:
   mvn test -Dtest=[name of test]


At the time of writing, PaxExam only works with junit-4, so you may not be
able to invoke them easily from your IDE.

Logging
-------
By default, very little log info comes out of the tests. If you wish to see more
logging information, you can control this from the command line.

There are 2 sources of logging information: 1) the pax environment  and 2) jetty logs.

To set the logging level for the pax environment use the following system property:

   mvn -Dpax.exam.LEVEL=[log level]

INFO, WARN and TRACE are known to work.

To set the logging level for the jetty logs edit the src/test/resources/jetty-logging.properties
to set the logging level you want and rerun your tests. The usual jetty logging levels apply.
