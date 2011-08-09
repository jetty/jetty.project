The ThreadMonitor is distributed as part of the jetty-monitor module. 

In order to start ThreadMonitor when server starts up, the following command line should be used.

    java -jar start.jar OPTIONS=monitor jetty-monitor.xml

To run ThreadMonitor on a Jetty installation that doesn't include jetty-monitor module, the jetty-monitor-[version].jar file needs to be copied into ${jetty.home}/lib/ext directory, and jetty-monitor.xml configuration file needs to be copied into ${jetty.home}/etc directory. Subsequently, the following command line should be used.

    java -jar start.jar etc/jetty-monitor.xml

If running Jetty on Java VM version 1.5, the -Dcom.sun.management.jmxremote option should be added to the command lines above in order to enable the JMX agent.

In order to log CPU utilization for threads that are above specified threshold, you need to follow instructions inside jetty-monitor.xml configuration file.