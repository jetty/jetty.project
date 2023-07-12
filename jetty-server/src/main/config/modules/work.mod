# DO NOT EDIT THIS FILE
# See https://eclipse.dev/jetty/documentation/ > Operations Guide > Jetty Start Mechanism

[description]
Creates the $JETTY_BASE/work directory as a persistent temp directory.
If a work directory exists, it is used for context temp directories, but
they are persisted between runs of Jetty, so generated files (eg JSPs)
can be kept.

[tags]
server

[files]
work/

