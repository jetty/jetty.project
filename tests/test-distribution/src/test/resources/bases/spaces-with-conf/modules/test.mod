[exec]
-showversion
-XX:+PrintCommandLineFlags
# This line is for JDK 21+ (the "gc*" is only supported on newest JVMs)
# -Xlog:gc*:file=/var/test/jetty-base/logs/gc.log:time,level,tags
# This line is for JDK 19 and older
-Xlog:gc:file=/var/test/jetty-base/logs/gc.log:time,level,tags
-XX:ErrorFile=/var/test/jetty-base/logs/jvm_crash_pid_%p.log