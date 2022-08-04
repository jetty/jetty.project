This empty jar file is purely to work around a problem with the Maven Dependency plugin.
Several modules in jetty use the Dependency plugin to copy or unpack the dependencies of  other modules.
However, the Dependency plugin is not capable of unpacking or copying a dependency of type 'pom', which
this module is, as it consists purely of external dependencies needed to run jsp.
