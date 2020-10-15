File buildLog = new File( basedir, 'build.log' )
assert buildLog.text.contains( 'Started Jetty Server' )
assert buildLog.text.contains( 'STARTED[class jettyissue.NormalClass]')
