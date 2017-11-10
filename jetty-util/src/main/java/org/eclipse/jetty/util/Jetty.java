//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

public class Jetty
{
    private static final Logger LOG = Log.getLogger( Jetty.class);

    public static final String VERSION;
    public static final String POWERED_BY;
    public static final boolean STABLE;
    public static final String GIT_HASH;

    /**
     * a formatted build timestamp with pattern yyyy-MM-dd'T'HH:mm:ssXXX
     */
    public static final String BUILD_TIMESTAMP;
    private static final Properties __buildProperties = new Properties( );

    static
    {
        try
        {
            try (InputStream inputStream = //
                     Jetty.class.getResourceAsStream( "/org/eclipse/jetty/version/build.properties" ))
            {
                __buildProperties.load( inputStream );
            }
        }
        catch ( Exception e )
        {
            LOG.ignore( e );
        }

        GIT_HASH = __buildProperties.getProperty( "buildNumber", "unknown" );
        System.setProperty( "jetty.git.hash" , GIT_HASH );
        BUILD_TIMESTAMP = formatTimestamp( __buildProperties.getProperty( "timestamp", "unknown" ));

        // using __buildProperties.getProperty("version") will contain version from the pom

        Package pkg = Jetty.class.getPackage();
        if (pkg != null &&
                "Eclipse.org - Jetty".equals(pkg.getImplementationVendor()) &&
                pkg.getImplementationVersion() != null)
            VERSION = pkg.getImplementationVersion();
        else
            VERSION = System.getProperty("jetty.version", "9.4.z-SNAPSHOT");

        POWERED_BY="<a href=\"http://eclipse.org/jetty\">Powered by Jetty:// "+VERSION+"</a>";

        // Show warning when RC# or M# is in version string
        STABLE = !VERSION.matches("^.*\\.(RC|M)[0-9]+$");
    }

    private Jetty()
    {
    }


    private static String formatTimestamp( String timestamp )
    {
        try
        {
            return new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ssXXX" )
                .format( new Date( Long.valueOf( timestamp ) ) );
        }
        catch ( NumberFormatException e )
        {
            LOG.debug( e );
            return "unknown";
        }
    }
    
}
