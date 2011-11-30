This bundle is made to inject the logback dependencies along with the slf4j dependencies to support log4j and commons-logging.
It will read the configuration in the jettyhome/resources/logback-test.xml or jettyhome/resources/logback.xml folder. 


It was tested with these bundles:
#this provides lg4j and commons-logging via slf4j
SLF4J = group("com.springsource.slf4j.api", "com.springsource.slf4j.org.apache.log4j", "com.springsource.slf4j.org.apache.commons.logging",
          :under=>"org.slf4j", :version=>"1.5.6")

#logback is not exporting enough packages for us to be able to configure logback classic programatically.. on the springsource version they are fine...
LOGBACK = group("com.springsource.ch.qos.logback.core", "com.springsource.ch.qos.logback.classic",
          :under=>"ch.qos.logback", :version=>"0.9.15")