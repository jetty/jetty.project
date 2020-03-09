# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Configure jetty logging to use Logback Logging. 
SLF4J is used as the core logging mechanism.

[tags]
logging

[depends]
resources

[provides]
logging

[files]
basehome:modules/logging/logback
maven://ch.qos.logback/logback-classic/${logback.version}|lib/logging/logback-classic-${logback.version}.jar
maven://ch.qos.logback/logback-core/${logback.version}|lib/logging/logback-core-${logback.version}.jar

[lib]
lib/logging/slf4j-api-${slf4j.version}.jar
lib/logging/logback-classic-${logback.version}.jar
lib/logging/logback-core-${logback.version}.jar

[ini]
slf4j.version?=2.0.0-alpha1
logback.version?=1.3.0-alpha5
jetty.webapp.addSystemClasses+=,org.slf4j.,ch.qos.logback.

[license]
Logback: the reliable, generic, fast and flexible logging framework.
Copyright (C) 1999-2012, QOS.ch. All rights reserved.

This program and the accompanying materials are dual-licensed under
either:

    the terms of the Eclipse Public License v1.0
    as published by the Eclipse Foundation:
    http://www.eclipse.org/legal/epl-v10.html

or (per the licensee's choosing) under

    the terms of the GNU Lesser General Public License version 2.1
    as published by the Free Software Foundation:
    http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
