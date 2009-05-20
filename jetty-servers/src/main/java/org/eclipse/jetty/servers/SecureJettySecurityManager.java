package org.eclipse.jetty.servers;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.security.Permission;
import java.util.ArrayList;
import java.util.List;

public class SecureJettySecurityManager
    extends SecurityManager
{
    public final static String __SET_SECURITY_MANAGER_DENIED = "set security manager denied";

    public final static String __PACKAGE_ACCESS_DENIED = "package access denied";

    public final static String __DENIED = "DENIED";

    private boolean _canSetSecurityManager = true; // TODO chintz test for basic functionality - remove

    private List<String> _deniedPackageList;

    public void addDeniedPackage( String packageName )
    {
        if ( _deniedPackageList == null )
        {
            _deniedPackageList = new ArrayList();
        }

        _deniedPackageList.add( packageName );
    }

    public boolean isCanSetSecurityManager()
    {
        return _canSetSecurityManager;
    }

    public void setCanSetSecurityManager( boolean setSecurityManager )
    {
        _canSetSecurityManager = setSecurityManager;
    }

    public void checkPermission( Permission perm )
    {
        // super.checkPermission(perm); // this blows up junit testing :(

        // System.out.println("SecureJettySecurityManager: checkPermission: " + perm.getName() );

        if ( perm.getName().equals( "setSecurityManager" ) )
        {
            if ( !isCanSetSecurityManager() )
            {
                throw new SecurityException( __SET_SECURITY_MANAGER_DENIED );
            }
        }
    }

    @Override
    public void checkAccept( String host, int port )
    {
        // TODO Auto-generated method stub
        super.checkAccept( host, port );

        System.out.println( "SecureJettySecurityManager: checkAccept: " + host + ": " + port );
    }

    @Override
    public void checkAccess( Thread t )
    {
        // TODO Auto-generated method stub
        super.checkAccess( t );

        System.out.println( "SecureJettySecurityManager: checkAccess: " + t.getName() );
    }

    @Override
    public void checkAccess( ThreadGroup g )
    {
        // TODO Auto-generated method stub
        super.checkAccess( g );

        System.out.println( "SecureJettySecurityManager: checkAccess: " + g.getName() );
    }

    @Override
    public void checkAwtEventQueueAccess()
    {
        // TODO Auto-generated method stub
        super.checkAwtEventQueueAccess();
    }

    @Override
    public void checkConnect( String host, int port, Object context )
    {
        // TODO Auto-generated method stub
        super.checkConnect( host, port, context );

        System.out.println( "SecureJettySecurityManager: checkConnect: " + host + ": " + port + ": "
            + context.getClass().getName() );
    }

    @Override
    public void checkConnect( String host, int port )
    {
        // TODO Auto-generated method stub
        super.checkConnect( host, port );

        System.out.println( "SecureJettySecurityManager: checkConnect: " + host + ": " + port );
    }

    @Override
    public void checkCreateClassLoader()
    {
        // TODO Auto-generated method stub
        super.checkCreateClassLoader();

        System.out.println( "SecureJettySecurityManager: checkCreateClassLoader" );
    }

    @Override
    public void checkDelete( String file )
    {
        // TODO Auto-generated method stub
        super.checkDelete( file );
    }

    @Override
    public void checkExec( String cmd )
    {
        // TODO Auto-generated method stub
        super.checkExec( cmd );
    }

    @Override
    public void checkExit( int status )
    {
        // TODO Auto-generated method stub
        super.checkExit( status );
    }

    @Override
    public void checkLink( String lib )
    {
        // TODO Auto-generated method stub
        super.checkLink( lib );
    }

    @Override
    public void checkListen( int port )
    {
        // TODO Auto-generated method stub
        super.checkListen( port );

        System.out.println( "SecureJettySecurityManager: checkListen: " + port );
    }

    @Override
    public void checkMemberAccess( Class<?> clazz, int which )
    {
        // TODO Auto-generated method stub
        super.checkMemberAccess( clazz, which );
    }

    @Override
    public void checkMulticast( InetAddress maddr, byte ttl )
    {
        // TODO Auto-generated method stub
        super.checkMulticast( maddr, ttl );
    }

    @Override
    public void checkMulticast( InetAddress maddr )
    {
        // TODO Auto-generated method stub
        super.checkMulticast( maddr );
    }

    @Override
    public void checkPackageAccess( String pkg )
    {
        System.out.println( "SecureJettySecurityManager: checkPackageAccess: " + pkg );
        
        super.checkPackageAccess( pkg );

        if ( _deniedPackageList != null )
        {
            if ( _deniedPackageList.contains( pkg ) )
            {
                throw new SecurityException( __PACKAGE_ACCESS_DENIED );
            }
        }
    }

    @Override
    public void checkPackageDefinition( String pkg )
    {
        System.out.println( "SecureJettySecurityManager: checkPackageDefinition: " + pkg );
        
        super.checkPackageDefinition( pkg );

        if ( _deniedPackageList != null )
        {
            if ( _deniedPackageList.contains( pkg ) )
            {
                throw new SecurityException( __PACKAGE_ACCESS_DENIED );
            }
        }

        
    }

    @Override
    public void checkPermission( Permission perm, Object context )
    {
        // TODO Auto-generated method stub
        super.checkPermission( perm, context );
    }

    @Override
    public void checkPrintJobAccess()
    {
        // TODO Auto-generated method stub
        super.checkPrintJobAccess();
    }

    @Override
    public void checkPropertiesAccess()
    {
        // TODO Auto-generated method stub
        super.checkPropertiesAccess();
    }

    @Override
    public void checkPropertyAccess( String key )
    {
        // TODO Auto-generated method stub
        super.checkPropertyAccess( key );
    }

    @Override
    public void checkRead( FileDescriptor fd )
    {
        // TODO Auto-generated method stub
        super.checkRead( fd );
    }

    @Override
    public void checkRead( String file, Object context )
    {
        // TODO Auto-generated method stub
        super.checkRead( file, context );
    }

    @Override
    public void checkRead( String file )
    {
        // TODO Auto-generated method stub
        super.checkRead( file );
    }

    @Override
    public void checkSecurityAccess( String target )
    {
        // TODO Auto-generated method stub
        super.checkSecurityAccess( target );
    }

    @Override
    public void checkSetFactory()
    {
        // TODO Auto-generated method stub
        super.checkSetFactory();
    }

    @Override
    public void checkSystemClipboardAccess()
    {
        throw new SecurityException( __DENIED );
    }

    @Override
    public boolean checkTopLevelWindow( Object window )
    {
        // TODO Auto-generated method stub
        return super.checkTopLevelWindow( window );
    }

    @Override
    public void checkWrite( FileDescriptor fd )
    {
        // TODO Auto-generated method stub
        super.checkWrite( fd );
    }

    @Override
    public void checkWrite( String file )
    {
        // TODO Auto-generated method stub
        super.checkWrite( file );
    }

    @Override
    protected int classDepth( String name )
    {
        // TODO Auto-generated method stub
        return super.classDepth( name );
    }

    @Override
    protected int classLoaderDepth()
    {
        // TODO Auto-generated method stub
        return super.classLoaderDepth();
    }

    @Override
    protected ClassLoader currentClassLoader()
    {
        // TODO Auto-generated method stub
        return super.currentClassLoader();
    }

    @Override
    protected Class<?> currentLoadedClass()
    {
        // TODO Auto-generated method stub
        return super.currentLoadedClass();
    }

    @Override
    protected Class[] getClassContext()
    {
        // TODO Auto-generated method stub
        return super.getClassContext();
    }

    @Override
    public boolean getInCheck()
    {
        // TODO Auto-generated method stub
        return super.getInCheck();
    }

    @Override
    public Object getSecurityContext()
    {
        // TODO Auto-generated method stub
        return super.getSecurityContext();
    }

    @Override
    public ThreadGroup getThreadGroup()
    {
        // TODO Auto-generated method stub
        return super.getThreadGroup();
    }

    @Override
    protected boolean inClass( String name )
    {
        // TODO Auto-generated method stub
        return super.inClass( name );
    }

    @Override
    protected boolean inClassLoader()
    {
        // TODO Auto-generated method stub
        return super.inClassLoader();
    }

}
