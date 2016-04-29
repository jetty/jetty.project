[description]
The default session store.  This module is typically never 
explictly enabled and is only used transitively when no other
session store module is enabled.  It does not configure anything
and defers the default behaviour to the SessionHandler

[provides]
session-store

[depends]
sessions

