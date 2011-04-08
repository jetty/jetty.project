

Cloudtide instance
===================

This directory contains overlays (as directories or jars) that 
are define instances of templates to be deployed.

An instance filename consists of a template name, the '=' character and 
then an instance identifier.  For example:

   mytemplate=redInstance

defines a instance called redInstance using the template called mytemplate.

An overlay may contain:
    * A context.xml file, which is an XmlConfiguration format file to 
      inject the ContextHandler instance. 
    * A web.xml fragment that is applied as an override descriptor
    * A cache.xml file which is an XmlConfiguration form file to inject 
      the ResourceCache
    * A webapp directory that contains static content that overlays the 
      static content of the context.
