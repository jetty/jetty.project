System.out.println( "running postbuild.groovy" )

File webfrag = new File(basedir, 'target/webfrag.xml')
assert webfrag.exists()
assert webfrag.text.contains("<servlet-name>org.apache.jsp.foo_jsp</servlet-name>")
assert webfrag.text.contains("<servlet-class>org.apache.jsp.foo_jsp</servlet-class>")
assert webfrag.text.contains("<url-pattern>/foo.jsp</url-pattern>")

// cannot use such parsing as it is not real xml
//def rootXml = new XmlSlurper().parse(new File(basedir, 'target/webfrag.xml'))

