package org.eclipse.jetty.embedded;

import org.eclipse.jetty.xml.XmlConfiguration;

public class TestXml
{
    public static void main(String[] args) throws Exception
    {
        System.setProperty("jetty.home","../jetty-distribution/target/distribution");
        XmlConfiguration.main(new String[]
            {
            "../jetty-jmx/src/main/config/etc/jetty-jmx.xml",
            "../jetty-server/src/main/config/etc/jetty.xml",
            "../jetty-spdy/spdy-jetty-http-webapp/src/main/config/etc/jetty-spdy.xml"
            }
        );
        
    }
}
