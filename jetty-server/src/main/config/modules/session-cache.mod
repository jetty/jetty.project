[description]
The default session cache.  This module is typically never 
explictly enabled and is only used transitively when no other
session cache module is enabled.  It does not configure anything
and defers the default behaviour to the SessionHandler

[provides]
session-cache

[depends]
sessions
