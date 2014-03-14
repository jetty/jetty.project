#
# Apache JSTL 
#

[lib]
lib/apache-jstl/*.jar

[ini-template]
# This module adds the Apache JSTL implementation to the server classpath
# This module is not needed if the glassfish JSP impl (module == jsp) is used, as it includes the glassfish version of jstl
# This module is needed if JSTL will be used by the apache JSP impl (module == apache-jsp) and there is no JSTL impl in the WEB-INF/lib of the webapp
