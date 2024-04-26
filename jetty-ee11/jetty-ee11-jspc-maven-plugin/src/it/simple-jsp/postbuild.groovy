import groovy.xml.XmlSlurper

System.out.println( "running postbuild.groovy" )

File webfrag = new File(basedir, 'target/webfrag.xml')
assert webfrag.exists()
assert webfrag.text.contains("<servlet-name>org.apache.jsp.foo_jsp</servlet-name>")
assert webfrag.text.contains("<servlet-class>org.apache.jsp.foo_jsp</servlet-class>")
assert webfrag.text.contains("<url-pattern>/foo.jsp</url-pattern>")

// cannot use such parsing as it is not real xml
//def rootXml = new XmlSlurper().parse(new File(basedir, 'target/webfrag.xml'))
// so fake it
def rootXml = new XmlSlurper().parseText("<root>"+webfrag.text+"</root>")

assert rootXml.servlet.'servlet-name'.text() == "org.apache.jsp.foo_jsp"
assert rootXml.servlet.'servlet-class'.text() == "org.apache.jsp.foo_jsp"

assert rootXml.'servlet-mapping'.'servlet-name'.text() == "org.apache.jsp.foo_jsp"
assert rootXml.'servlet-mapping'.'url-pattern'.text() == "/foo.jsp"
