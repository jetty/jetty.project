[exec]
-showversion
-XX:+PrintCommandLineFlags
-Xlog:gc*:file=/var/test/jetty-base/logs/gc.log:time,level,tags
-XX:ErrorFile=/var/test/jetty-base/logs/jvm_crash_pid_%p.log