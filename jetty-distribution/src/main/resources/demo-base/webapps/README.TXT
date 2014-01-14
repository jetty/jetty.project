
This directory is scanned by the demo WebAppDeployer provider 
created in the etc/jetty-demo.xml file and enabled by the 
start.d/900-demo.ini file.

To disable the demo, either remove the start.d/900-demo.ini or issue the following command:

  java -jar start.jar --disable=demo


For normal webapp deployment, use the webapps directory.

