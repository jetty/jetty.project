
This directory is scanned by the WebAppDeployer provider for
web applications to deploy.   It may contain:

 + standard WAR files
 + directories that are deployed as static content, except for
   directories ending in ".d", which are ignored.
 + Context XML files that contain XmlConfiguration which 
   describe individual contexts to be deployed to the server.  
   
This directory is scanned for additions, removals and updates 
for hot deployment.

