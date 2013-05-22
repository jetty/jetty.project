

Cloudtide nodes
===============

This directory contains overlays (as directories or jars) that 
are applied to all instances for a given node. 

An overlay is structed as a WAR, but also may contain:
    * WEB-INF/lib-overlay directory, which can contain jar files made available to 
      the overlay.xml configuration (unlike any overlayed WEB-INF/lib jars).

    * A WEB-INF/overlay.xml file, which is an XmlConfiguration format file used to 
      inject the ContextHandler instance. 

    * A WEB-INF/web-overlay.xml fragment that is applied as an override descriptor

    * Static content that overlays the static content of the webapp, node and template.

    * WEB-INF/classes and WEB-INF/lib that overlays the code of the webapp, node and template.
