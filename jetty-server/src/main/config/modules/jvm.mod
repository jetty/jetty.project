
INI=#===========================================================
INI=# Configure JVM arguments.
INI=# If JVM args are include in an ini file then --exec is needed
INI=# to start a new JVM from start.jar with the extra args.
INI=# If you wish to avoid an extra JVM running, place JVM args
INI=# on the normal command line and do not use --exec
INI=#-----------------------------------------------------------
INI=# --exec
INI=# -Xmx2000m
INI=# -Xmn512m
INI=# -XX:+UseConcMarkSweepGC
INI=# -XX:ParallelCMSThreads=2
INI=# -XX:+CMSClassUnloadingEnabled  
INI=# -XX:+UseCMSCompactAtFullCollection
INI=# -XX:CMSInitiatingOccupancyFraction=80
INI=# -verbose:gc
INI=# -XX:+PrintGCDateStamps
INI=# -XX:+PrintGCTimeStamps
INI=# -XX:+PrintGCDetails
INI=# -XX:+PrintTenuringDistribution
INI=# -XX:+PrintCommandLineFlags
INI=# -XX:+DisableExplicitGC
INI=# -Dorg.apache.jasper.compiler.disablejsr199=true