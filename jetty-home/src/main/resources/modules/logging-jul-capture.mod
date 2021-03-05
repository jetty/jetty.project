# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Captures java.util.logging events and bridges them to slf4j.

[tags]
logging

[depends]
logging/slf4j
logging

[provides]
java-util-logging

[files]
maven://org.slf4j/jul-to-slf4j/${slf4j.version}|lib/logging/jul-to-slf4j-${slf4j.version}.jar
basehome:modules/logging/jul/resources/java-util-logging-bridge.properties|resources/java-util-logging.properties

[lib]
lib/logging/jul-to-slf4j-${slf4j.version}.jar

[exec]
-Djava.util.logging.config.file=${jetty.base}/resources/java-util-logging.properties

[license]
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
