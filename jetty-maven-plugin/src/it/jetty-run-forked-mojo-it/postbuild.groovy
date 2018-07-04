

System.out.println( "running postbuild.groovy port " + jettyStopPort + ", key:" + jettyStopKey )

int port = Integer.parseInt( jettyStopPort )

Socket s=new Socket(InetAddress.getByName("127.0.0.1"),port )

OutputStream out=s.getOutputStream()
out.write(( jettyStopKey +"\r\nforcestop\r\n").getBytes())
out.flush()
s.close()


File buildLog = new File( basedir, 'build.log' )
assert buildLog.text.contains( 'Forked process starting' )
assert buildLog.text.contains( 'Running org.eclipse.jetty.maven.plugin.it.TestGetContent')
assert buildLog.text.contains( 'pingServlet ok')
assert buildLog.text.contains( 'helloServlet')
