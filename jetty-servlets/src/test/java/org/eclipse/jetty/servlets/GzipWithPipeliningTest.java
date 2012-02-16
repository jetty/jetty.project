package org.eclipse.jetty.servlets;

import static org.hamcrest.Matchers.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.gzip.Hex;
import org.eclipse.jetty.servlets.gzip.NoOpOutputStream;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Test the effects of Gzip filtering when in the context of HTTP/1.1 Pipelining.
 */
public class GzipWithPipeliningTest
{
    @Rule
    public TestingDir testingdir = new TestingDir();
    
    private Server server;
    private URI serverUri;

    @Before
    public void startServer() throws Exception
    {
        // Configure Server
        server = new Server(0);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        DefaultServlet servlet = new DefaultServlet();
        ServletHolder holder = new ServletHolder(servlet);
        holder.setInitParameter("resourceBase",MavenTestingUtils.getTestResourcesDir().getAbsolutePath());
        context.addServlet(holder,"/");

        FilterHolder filter = context.addFilter(GzipFilter.class,"/*",0);
        filter.setInitParameter("mimeTypes","text/plain");

        server.setHandler(context);

        // Start Server
        server.start();

        Connector conn = server.getConnectors()[0];
        String host = conn.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = conn.getLocalPort();
        serverUri = new URI(String.format("ws://%s:%d/",host,port));
        // System.out.printf("Server URI: %s%n",serverUri);
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testGzipThenImagePipelining() throws Exception
    {
        testingdir.ensureEmpty();
        File outputDir = testingdir.getDir(); 
        
        PipelineHelper client = new PipelineHelper(serverUri);

        try
        {
            File txtFile = MavenTestingUtils.getTestResourceFile("lots-of-fantasy-names.txt");
            File pngFile = MavenTestingUtils.getTestResourceFile("jetty_logo.png");

            // Size of content, as it exists on disk, without gzip compression.
            long rawsize = txtFile.length() + pngFile.length();
            Assert.assertThat("Ensure that we have sufficient file size to trigger chunking",rawsize,greaterThan(300000L));

            String respHeader;

            client.connect();

            // Request text that will be gzipped + chunked in the response
            client.issueGET("/lots-of-fantasy-names.txt",true);

            respHeader = client.readResponseHeader();
            System.out.println("Response Header #1 --\n" + respHeader);
            Assert.assertThat("Content-Encoding should be gzipped",respHeader,containsString("Content-Encoding: gzip\r\n"));
            Assert.assertThat("Transfer-Encoding should be chunked",respHeader,containsString("Transfer-Encoding: chunked\r\n"));

            // Raw output / gzipped, writted to disk (checked for sha1sum later)
            File rawOutputFile = new File(outputDir, "response-1.gz");
            FileOutputStream rawOutputStream = new FileOutputStream(rawOutputFile);

            long chunkSize = client.readChunkSize();
            System.out.println("Chunk Size: " + chunkSize);

            // Read only 20% - intentionally a partial read.
            System.out.println("Attempting to read partial content ...");
            int readBytes = client.readBody(rawOutputStream,(int)((float)chunkSize * 0.20f));
            System.out.printf("Read %,d bytes%n",readBytes);

            // Issue another request
            client.issueGET("/jetty_logo.png",true);

            // Finish reading chunks
            System.out.println("Finish reading remaining chunks ...");
            String line;
            chunkSize = chunkSize - readBytes;
            while (chunkSize > 0)
            {
                readBytes = client.readBody(rawOutputStream,(int)chunkSize);
                System.out.printf("Read %,d bytes%n",readBytes);
                line = client.readLine();
                Assert.assertThat("Chunk delim should be an empty line with CR+LF",line,is(""));
                chunkSize = client.readChunkSize();
                System.out.printf("Next Chunk: (0x%X) %,d bytes%n",chunkSize,chunkSize);
            }

            // Inter-pipeline delim
            line = client.readLine();
            Assert.assertThat("Inter-pipeline delim should be an empty line with CR+LF",line,is(""));
            
            // Sha1tracking for 1st Request
            MessageDigest digestTxt = MessageDigest.getInstance("SHA1");
            DigestOutputStream digesterTxt = new DigestOutputStream(new NoOpOutputStream(),digestTxt);

            // Decompress 1st request and calculate sha1sum
            IO.close(rawOutputStream);
            FileInputStream rawInputStream = new FileInputStream(rawOutputFile);
            GZIPInputStream ungzipStream = new GZIPInputStream(rawInputStream);
            IO.copy(ungzipStream, digesterTxt);
            
            // Read 2nd request http response header
            respHeader = client.readResponseHeader();
            System.out.println("Response Header #2 --\n" + respHeader);
            Assert.assertThat("Content-Encoding should NOT be gzipped",respHeader,not(containsString("Content-Encoding: gzip\r\n")));
            Assert.assertThat("Transfer-Encoding should NOT be chunked",respHeader,not(containsString("Transfer-Encoding: chunked\r\n")));

            // Sha1tracking for 2nd Request
            MessageDigest digestImg = MessageDigest.getInstance("SHA1");
            DigestOutputStream digesterImg = new DigestOutputStream(new NoOpOutputStream(),digestImg);

            // Read 2nd request body
            int contentLength = client.getContentLength(respHeader);
            Assert.assertThat("Image Content Length",(long)contentLength,is(pngFile.length()));
            client.readBody(digesterImg,contentLength);

            // Validate checksums
            IO.close(rawOutputStream);
            assertChecksum("lots-of-fantasy-names.txt",digestTxt);
            IO.close(digesterImg);
            assertChecksum("jetty_logo.png",digestImg);
        }
        finally
        {
            client.disconnect();
        }
    }

    private void assertChecksum(String testResourceFile, MessageDigest digest) throws IOException
    {
        String expectedSha1 = loadSha1sum(testResourceFile + ".sha1");
        String actualSha1 = Hex.asHex(digest.digest());
        Assert.assertEquals(testResourceFile + " / SHA1Sum of content",expectedSha1,actualSha1);
    }

    private String loadSha1sum(String testResourceSha1Sum) throws IOException
    {
        File sha1File = MavenTestingUtils.getTestResourceFile(testResourceSha1Sum);
        String contents = IO.readToString(sha1File);
        Pattern pat = Pattern.compile("^[0-9A-Fa-f]*");
        Matcher mat = pat.matcher(contents);
        Assert.assertTrue("Should have found HEX code in SHA1 file: " + sha1File,mat.find());
        return mat.group();
    }

}
