Jetty start.d ini directory
===========================

This start.d directory contains *.ini files that are appended to the effective command line
used to start jetty by the command:

  java -jar start.jar

Each line in these files is prepended to the real command line as arguments, and may be either:
 + A property like: name=value
 + A module to enable like: --module=jmx
 + An XML configuration file like: etc/jetty-feature.xml
 + A start.jar option like: --exec
 
If --exec or --dry-run are used, then these files may also contain lines with:
 + A JVM option like: -Xmx2000m 
 + A System Property like: -Dcom.sun.management.jmxremote


A template ini file may be created for known modules by using the --ini option.  
For example to create an ini templates for https use the command

  java -jar start.jar --ini=https

