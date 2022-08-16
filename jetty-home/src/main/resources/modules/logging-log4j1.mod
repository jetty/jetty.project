# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Configures Jetty logging to use Log4j.
SLF4J is used as the core logging mechanism.

[deprecated]
Module 'logging-log4j1' is deprecated for removal.
Use 'logging-log4j2' instead.

[tags]
logging
deprecated

[depends]
logging/slf4j
resources

[provides]
logging
log4j

[files]
basehome:modules/logging/log4j1
maven://log4j/log4j/${log4j.version}|lib/logging/log4j-${log4j.version}.jar
maven://org.slf4j/slf4j-reload4j/${slf4j.version}|lib/logging/slf4j-reload4j-${slf4j.version}.jar

[lib]
lib/logging/slf4j-reload4j-${slf4j.version}.jar
lib/logging/log4j-${log4j.version}.jar

[ini]
log4j.version?=1.2.17
jetty.webapp.addServerClasses+=,org.apache.log4j.


[license]
Log4j is released under the Apache 2.0 license.
http://www.apache.org/licenses/LICENSE-2.0.html


SLF4J is distributed under the MIT License.
Copyright (c) 2004-2013 QOS.ch
All rights reserved.

Permission is hereby granted, free  of charge, to any person obtaining
a  copy  of this  software  and  associated  documentation files  (the
"Software"), to  deal in  the Software without  restriction, including
without limitation  the rights to  use, copy, modify,  merge, publish,
distribute,  sublicense, and/or sell  copies of  the Software,  and to
permit persons to whom the Software  is furnished to do so, subject to
the following conditions:

The  above  copyright  notice  and  this permission  notice  shall  be
included in all copies or substantial portions of the Software.

THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE,  ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
