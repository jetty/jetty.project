

System.out.println( "running postbuild.groovy port " + jettyStopPort + ", key:" + jettyStopKey )

int port = Integer.parseInt( jettyStopPort )

Socket s=new Socket(InetAddress.getByName("127.0.0.1"),port )
s.setSoLinger(false, 0)

OutputStream out=s.getOutputStream()
out.write(( jettyStopKey +"\r\nforcestop\r\n").getBytes())
out.flush()
s.close()