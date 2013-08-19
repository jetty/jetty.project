
This start.d directory contains modular ini files that are appended to the effective command line
used to start jetty by the command:

  java -jar start.jar


The modules are executed in alphabetic order and the 'NNN-' prefixes are a convention to make that ordering
explicit regardless of module name.

All modules ending with '.ini' will be executed and it is a convention that disabled modules are renamed to
end with '.ini.disabled'.   The start.jar mechanism can be used to enable/disable modules with commands like:

  java -jar start.jar --enable=ssl --enable=spdy

Note that the numeric prefix can be skipped in such commands.


The ini modules have a simple dependency mechanism so a module can declare if it depends on or conflicts with
other modules.  For example the spdy module includes the entries:

    EXCLUDE=https
    DEPEND=npn,ssl

This says that spdy cannot be used with the https module (both use the same port and spdy provides https) and
that the npn and ssl modules must also be enabled. 
