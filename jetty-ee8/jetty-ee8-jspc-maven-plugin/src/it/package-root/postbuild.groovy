

System.out.println( "running postbuild.groovy" )

File file = new File( basedir, "target/classes/org/eclipse/jetty/ee8/test/foo_jsp.class" );
if ( !file.isFile() )
{
    throw new FileNotFoundException( "Could not find generated class in the proper package name: " + file );
}
