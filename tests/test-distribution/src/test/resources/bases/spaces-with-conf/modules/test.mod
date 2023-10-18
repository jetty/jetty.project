[exec]
-showversion
-XX:+PrintCommandLineFlags
-Xlog:gc*:file=/tmp/logs/gc.log:time,level,tags
-XX:ErrorFile=/tmp/logs/jvm_crash_pid_%p.log