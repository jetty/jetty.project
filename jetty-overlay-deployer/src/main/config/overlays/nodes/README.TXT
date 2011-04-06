

Cloudtide nodes
===============

This directory contains overlays (as directories or jars) that 
are applied to all instances for a given node. 

An overlay may contain:
    * A context.xml file, which is an XmlConfiguration format file to 
      inject the ContextHandler instance. 
    * A web.xml fragment that is applied as an override descriptor
    * A cache.xml file which is an XmlConfiguration form file to inject 
      the ResourceCache
    * A webapp directory that contains static content that overlays the 
      static content of the context.
