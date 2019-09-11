import java.util.jar.*

File artifact = new File( basedir, "target/jetty-runner.jar" )
assert artifact.exists()

JarFile jar = new JarFile( artifact )

Attributes manifest = jar.getManifest().getMainAttributes()

assert manifest.getValue( new Attributes.Name( "Main-Class" ) ).equals( "org.eclipse.jetty.runner.Runner" )
