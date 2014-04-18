#
# Jetty JSP Module
#

[depend]
jsp
jsp-impl/${jsp-impl}-jstl

[ini-template]
# JSTL Configuration
# The glassfish jsp-impl includes JSTL by default and this module
# is not required to activate it.
# The apache jsp-impl does not include JSTL by default and this module
# is required to put JSTL on the container classpath
