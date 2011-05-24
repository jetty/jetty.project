
Cloudtide templates
===================

This directory contains overlays (as directories or jars) that 
are applied to webapps to create templates. Templates are not directly
deployed, but are used by overlays in the instances directory.

A Template filename consists of a template name, the '=' character and 
then the webapp name.  For example:

   mytemplate=mywebapp-1.2.3

This defines a template called mytemplate that overlays either the mywebapp-1.2.3.war 
file or the mywebapp-1.2.3 directory in cloudtide/webapps.

An overlay is structed as a WAR, and may contain:
    * WEB-INF/template.xml a XmlConfiguration formatted file that is applied to a shared 
      instance of TemplateContext to provide the common classloader and resource cache to
      all instances of the template.

    * WEB-INF/lib-overlay directory, which can contain jar files made available to 
      the overlay.xml configuration (unlike any overlayed WEB-INF/lib jars).

    * A WEB-INF/overlay.xml file, which is an XmlConfiguration format file used to 
      inject the ContextHandler instance. 

    * A WEB-INF/web-overlay.xml fragment that is applied as an override descriptor

    * Static content that overlays the static content of the webapp, node and template.

    * WEB-INF/classes and WEB-INF/lib that overlays the code of the webapp, node and template.
