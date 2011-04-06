
Cloudtide templates
===================

This directory contains overlays (as directories or jars) that 
are applied to webapps to customize them. Templates are not directly
deployed, but are used by overlays in the instances directory.

A Template filename consists of a template name, the '=' character and 
then the webapp name.  For example:

   mytemplate=mywebapp-1.2.3

defines a template called mytemplate that overlays either the mywebapp-1.2.3.war 
file or the mywebapp-1.2.3 directory in cloudtide/webapps.

An overlay may contain:
    * A context.xml file, which is an XmlConfiguration format file to 
      inject the ContextHandler instance. 
    * A web.xml fragment that is applied as an override descriptor
    * A cache.xml file which is an XmlConfiguration form file to inject 
      the ResourceCache
    * A webapp directory that contains static content that overlays the 
      static content of the context.
