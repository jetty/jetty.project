package org.eclipse.jetty.servlet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import junit.framework.AssertionFailedError;
import junit.framework.TestCase;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;

public class DefaultServletTest extends TestCase
{
    private boolean _runningOnWindows;
    private Server server;
    private LocalConnector connector;
    private ServletContextHandler context;

    protected void setUp() throws Exception
    {
        _runningOnWindows = System.getProperty( "os.name" ).startsWith( "Windows" );

        super.setUp();

        server = new Server();
        server.setSendServerVersion(false);

        connector = new LocalConnector();

        context = new ServletContextHandler();
        context.setContextPath("/context");
        context.setWelcomeFiles(new String[] {"index.html","index.jsp","index.htm"}); 

        server.setHandler(context);
        server.addConnector(connector);

        server.start();
    }

    protected void tearDown() throws Exception
    {
        super.tearDown();

        if (server != null)
        {
            server.stop();
        }
    }

    public void testListingWithSession() throws Exception
    {
        ServletHolder defholder = context.addServlet(DefaultServlet.class,"/*");
        defholder.setInitParameter("dirAllowed","true");
        defholder.setInitParameter("redirectWelcome","false");
        defholder.setInitParameter("gzip","false");

        File testDir = new File("target/tests/" + getName());
        prepareEmptyTestDir(testDir);

        /* create some content in the docroot */
        File resBase = new File(testDir, "docroot");
        resBase.mkdirs();
        new File(resBase, "one").mkdir();
        new File(resBase, "two").mkdir();
        new File(resBase, "three").mkdir();

        String resBasePath = resBase.getAbsolutePath();
        defholder.setInitParameter("resourceBase",resBasePath);

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /context/;JSESSIONID=1234567890 HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("\n");

        String response = connector.getResponses(req1.toString());

        assertResponseContains("/one/;JSESSIONID=1234567890",response);
        assertResponseContains("/two/;JSESSIONID=1234567890",response);
        assertResponseContains("/three/;JSESSIONID=1234567890",response);

        assertResponseNotContains("<script>",response);
    }

    public void testListingXSS() throws Exception
    {
        ServletHolder defholder = context.addServlet(DefaultServlet.class,"/*");
        defholder.setInitParameter("dirAllowed","true");
        defholder.setInitParameter("redirectWelcome","false");
        defholder.setInitParameter("gzip","false");

        File testDir = new File("target/tests/" + getName());
        prepareEmptyTestDir(testDir);

        /* create some content in the docroot */
        File resBase = new File(testDir, "docroot");
        resBase.mkdirs();
        new File(resBase, "one").mkdir();
        new File(resBase, "two").mkdir();
        new File(resBase, "three").mkdir();
        if ( !_runningOnWindows )
            assertTrue("Creating dir 'f??r' (Might not work in Windows)", new File(resBase, "f??r").mkdir());

        String resBasePath = resBase.getAbsolutePath();
        defholder.setInitParameter( "resourceBase", resBasePath );

        StringBuffer req1 = new StringBuffer();
        /*
         * Intentionally bad request URI. Sending a non-encoded URI with typically encoded characters '<', '>', and
         * '"'.
         */
        req1.append( "GET /context/;<script>window.alert(\"hi\");</script> HTTP/1.1\n" );
        req1.append( "Host: localhost\n" );
        req1.append( "\n" );

        String response = connector.getResponses( req1.toString() );

        assertResponseContains( "/one/", response );
        assertResponseContains( "/two/", response );
        assertResponseContains( "/three/", response );
        if ( !_runningOnWindows )
            assertResponseContains( "/f%3F%3Fr", response );

        assertResponseNotContains( "<script>", response );
    }

    public void testListingProperUrlEncoding() throws Exception
    {
        ServletHolder defholder = context.addServlet(DefaultServlet.class,"/*");
        defholder.setInitParameter("dirAllowed","true");
        defholder.setInitParameter("redirectWelcome","false");
        defholder.setInitParameter("gzip","false");

        File testDir = new File("target/tests/" + getName());
        prepareEmptyTestDir(testDir);

        /* create some content in the docroot */
        File resBase = new File(testDir, "docroot");
        resBase.mkdirs();
        File wackyDir = new File(resBase, "dir;"); // this should not be double-encoded.
        assertTrue(wackyDir.mkdirs());

        new File(wackyDir, "four").mkdir();
        new File(wackyDir, "five").mkdir();
        new File(wackyDir, "six").mkdir();

        /* At this point we have the following
         * testListingProperUrlEncoding/
         * `-- docroot
         *     `-- dir;
         *         |-- five
         *         |-- four
         *         `-- six
         */

        String resBasePath = resBase.getAbsolutePath();
        defholder.setInitParameter("resourceBase",resBasePath);

        // First send request in improper, unencoded way.
        String response = connector.getResponses("GET /context/dir;/ HTTP/1.0\r\n\r\n");

        assertResponseContains("HTTP/1.1 404 Not Found", response);


        // Now send request in proper, encoded format.
        response = connector.getResponses("GET /context/dir%3B/ HTTP/1.0\r\n\r\n");

        // Should not see double-encoded ";"
        // First encoding: ";" -> "%3b"
        // Second encoding: "%3B" -> "%253B" (BAD!)
        assertResponseNotContains("%253B",response);

        assertResponseContains("/dir%3B/",response);
        assertResponseContains("/dir%3B/four/",response);
        assertResponseContains("/dir%3B/five/",response);
        assertResponseContains("/dir%3B/six/",response);
    }

    public void testListingContextBreakout() throws Exception
    {
        ServletHolder defholder = context.addServlet(DefaultServlet.class,"/");
        defholder.setInitParameter("dirAllowed","true");
        defholder.setInitParameter("redirectWelcome","false");
        defholder.setInitParameter("gzip","false");
        defholder.setInitParameter("aliases","true");

        File testDir = new File("target/tests/" + getName());
        prepareEmptyTestDir(testDir);

        /* create some content in the docroot */
        File resBase = new File(testDir, "docroot");
        resBase.mkdirs();

        File index = new File(resBase, "index.html");
        createFile(index, "<h1>Hello Index</h1>");

        File wackyDir = new File(resBase, "dir?");
        if ( !_runningOnWindows )
        {
            assertTrue(wackyDir.mkdirs());
        }

        wackyDir = new File(resBase, "dir;");
        assertTrue(wackyDir.mkdirs());

        /* create some content outside of the docroot */
        File sekret = new File(testDir, "sekret");
        sekret.mkdirs();
        File pass = new File(sekret, "pass");
        createFile(pass, "Sssh, you shouldn't be seeing this");

        /* At this point we have the following
         * testListingContextBreakout/
         * |-- docroot
         * |   |-- index.html
         * |   |-- dir?
         * |   |-- dir;
         * `-- sekret
         *     `-- pass
         */

        String resBasePath = resBase.getAbsolutePath();
        defholder.setInitParameter("resourceBase",resBasePath);

        String response;

        response= connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Index</h1>",response);

        response= connector.getResponses("GET /context/dir?/ HTTP/1.0\r\n\r\n");
        assertResponseContains("404",response);

        if ( !_runningOnWindows )
        {
            response= connector.getResponses("GET /context/dir%3F/ HTTP/1.0\r\n\r\n");
            assertResponseContains("Directory: /context/dir?/<",response);
        }
        else
            assertResponseContains("404",response);

        response= connector.getResponses("GET /context/index.html HTTP/1.0\r\n\r\n");
        assertResponseContains("Hello Index",response);

        response= connector.getResponses("GET /context/dir%3F/../index.html HTTP/1.0\r\n\r\n");
        assertResponseContains("Hello Index",response);

        response= connector.getResponses("GET /context/dir%3F/../../ HTTP/1.0\r\n\r\n");
        assertResponseNotContains("Directory: ",response);

        response= connector.getResponses("GET /context/dir%3F/../../sekret/pass HTTP/1.0\r\n\r\n");
        assertResponseNotContains("Sssh",response);

        response= connector.getResponses("GET /context/dir?/../../ HTTP/1.0\r\n\r\n");
        assertResponseNotContains("Directory: ",response);

        response= connector.getResponses("GET /context/dir?/../../sekret/pass HTTP/1.0\r\n\r\n");
        assertResponseNotContains("Sssh",response);

        response= connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Index</h1>",response);

        response= connector.getResponses("GET /context/dir;/ HTTP/1.0\r\n\r\n");
        assertResponseContains("404",response);

        response= connector.getResponses("GET /context/dir%3B/ HTTP/1.0\r\n\r\n");
        assertResponseContains("Directory: /context/dir;/<",response);

        response= connector.getResponses("GET /context/index.html HTTP/1.0\r\n\r\n");
        assertResponseContains("Hello Index",response);

        response= connector.getResponses("GET /context/dir%3B/../index.html HTTP/1.0\r\n\r\n");
        assertResponseContains("Hello Index",response);

        response= connector.getResponses("GET /context/dir%3B/../../ HTTP/1.0\r\n\r\n");
        assertResponseNotContains("Directory: ",response);

        response= connector.getResponses("GET /context/dir%3B/../../sekret/pass HTTP/1.0\r\n\r\n");
        assertResponseNotContains("Sssh",response);

        response= connector.getResponses("GET /context/dir;/../../ HTTP/1.0\r\n\r\n");
        assertResponseNotContains("Directory: ",response);

        response= connector.getResponses("GET /context/dir;/../../sekret/pass HTTP/1.0\r\n\r\n");
        assertResponseNotContains("Sssh",response);
    }



    public void testWelcome() throws Exception
    {
        File testDir = new File("target/tests/" + getName());
        prepareEmptyTestDir(testDir);
        File resBase = new File(testDir, "docroot");
        resBase.mkdirs();
        File inde = new File(resBase, "index.htm");
        File index = new File(resBase, "index.html");
        

        String resBasePath = resBase.getAbsolutePath();

        ServletHolder defholder = context.addServlet(DefaultServlet.class,"/");
        defholder.setInitParameter("dirAllowed","false");
        defholder.setInitParameter("redirectWelcome","false");
        defholder.setInitParameter("welcomeServlets","false");
        defholder.setInitParameter("gzip","false");
        defholder.setInitParameter("resourceBase",resBasePath);
        
        ServletHolder jspholder = context.addServlet(NoJspServlet.class,"*.jsp");

        String response;

        response= connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("403",response);

        createFile(index, "<h1>Hello Index</h1>");
        response= connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Index</h1>",response);
        
        createFile(inde, "<h1>Hello Inde</h1>");
        response= connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Index</h1>",response);

        index.delete();
        response= connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Inde</h1>",response);
        
        inde.delete();
        response= connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("403",response);

    }

    public void testWelcomeServlet() throws Exception
    {
        File testDir = new File("target/tests/" + getName());
        prepareEmptyTestDir(testDir);
        File resBase = new File(testDir, "docroot");
        resBase.mkdirs();
        File inde = new File(resBase, "index.htm");
        File index = new File(resBase, "index.html");
        

        String resBasePath = resBase.getAbsolutePath();

        ServletHolder defholder = context.addServlet(DefaultServlet.class,"/");
        defholder.setInitParameter("dirAllowed","false");
        defholder.setInitParameter("redirectWelcome","false");
        defholder.setInitParameter("welcomeServlets","true");
        defholder.setInitParameter("gzip","false");
        defholder.setInitParameter("resourceBase",resBasePath);
        
        ServletHolder jspholder = context.addServlet(NoJspServlet.class,"*.jsp");

        String response;

        response= connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("JSP support not configured",response);

        createFile(index, "<h1>Hello Index</h1>");
        response= connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Index</h1>",response);
        
        createFile(inde, "<h1>Hello Inde</h1>");
        response= connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Index</h1>",response);

        index.delete();
        response= connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Inde</h1>",response);
        
        inde.delete();
        response= connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("JSP support not configured",response);

    }
    
    private void createFile(File file, String str) throws IOException
    {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(str.getBytes());
            out.flush();
        } finally {
            IO.close(out);
        }
    }

    private void prepareEmptyTestDir(File testdir)
    {
        if (testdir.exists())
        {
            emptyDir(testdir);
        }
        else
        {
            testdir.mkdirs();
        }

        assertTrue("test dir should exists",testdir.exists());
        assertTrue("test dir should be a dir",testdir.isDirectory());
        assertTrue("test dir should be empty",isEmpty(testdir));
    }

    private boolean isEmpty(File dir)
    {
        if (!dir.isDirectory())
        {
            return true;
        }

        return dir.list().length == 0;
    }

    private void emptyDir(File dir)
    {
        File entries[] = dir.listFiles();
        for (int i = 0; i < entries.length; i++)
        {
            deletePath(entries[i]);
        }
    }

    private void deletePath(File path)
    {
        if (path.isDirectory())
        {
            File entries[] = path.listFiles();
            for (int i = 0; i < entries.length; i++)
            {
                deletePath(entries[i]);
            }
        }

        assertTrue("Deleting: " + path.getAbsolutePath(),path.delete());
    }

    private void assertResponseNotContains(String forbidden, String response)
    {
        int idx = response.indexOf(forbidden);
        if (idx != (-1))
        {
            // Found (when should not have)
            StringBuffer err = new StringBuffer();
            err.append("Response contain forbidden string \"").append(forbidden).append("\"");
            err.append("\n").append(response);

            System.err.println(err);
            throw new AssertionFailedError(err.toString());
        }
    }

    private void assertResponseContains(String expected, String response)
    {
        int idx = response.indexOf(expected);
        if (idx == (-1))
        {
            // Not found
            StringBuffer err = new StringBuffer();
            err.append("Response does not contain expected string \"").append(expected).append("\"");
            err.append("\n").append(response);

            System.err.println(err);
            throw new AssertionFailedError(err.toString());
        }
    }
}
