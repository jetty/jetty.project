package org.eclipse.jetty.servlets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StdErrLog;
import org.junit.Assert;

import static org.hamcrest.Matchers.not;

public class PipelineHelper
{
    private static final Logger LOG = Log.getLogger(PipelineHelper.class);
    private URI uri;
    private SocketAddress endpoint;
    private Socket socket;
    private OutputStream outputStream;
    private InputStream inputStream;

    public PipelineHelper(URI uri)
    {
        if (LOG instanceof StdErrLog)
        {
            ((StdErrLog)LOG).setLevel(StdErrLog.LEVEL_DEBUG);
        }
        this.uri = uri;
        this.endpoint = new InetSocketAddress(uri.getHost(),uri.getPort());
    }

    /**
     * Open the Socket to the destination endpoint and
     *
     * @return the open java Socket.
     * @throws IOException
     */
    public Socket connect() throws IOException
    {
        LOG.info("Connecting to endpoint: " + endpoint);
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(endpoint,1000);

        outputStream = socket.getOutputStream();
        inputStream = socket.getInputStream();

        return socket;
    }

    /**
     * Issue a HTTP/1.1 GET request with Connection:keep-alive set.
     *
     * @param path
     *            the path to GET
     * @param acceptGzipped
     *            to turn on acceptance of GZIP compressed responses
     * @throws IOException
     */
    public void issueGET(String path, boolean acceptGzipped, boolean close) throws IOException
    {
        LOG.debug("Issuing GET on " + path);
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(uri.resolve(path).getPath()).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(uri.getHost()).append(":").append(uri.getPort()).append("\r\n");
        req.append("User-Agent: Mozilla/5.0 (iPhone; CPU iPhone OS 5_0_1 like Mac OS X) AppleWebKit/534.46 (KHTML, like Gecko) Version/5.1 Mobile/9A405 Safari/7534.48.3\r\n");
        req.append("Accept: */*\r\n");
        req.append("Referer: http://mycompany.com/index.html\r\n");
        req.append("Accept-Language: en-us\r\n");
        if (acceptGzipped)
        {
            req.append("Accept-Encoding: gzip, deflate\r\n");
        }
        req.append("Cookie: JSESSIONID=spqx8v8szylt1336t96vc6mw0\r\n");
        if ( close )
        {
            req.append("Connection: close\r\n");
        }
        else
        {
            req.append("Connection: keep-alive\r\n");
        }

        req.append("\r\n");

        LOG.debug("Request:" + req);

        // Send HTTP GET Request
        byte buf[] = req.toString().getBytes();
        outputStream.write(buf,0,buf.length);
        outputStream.flush();
    }

    public String readResponseHeader() throws IOException
    {
        // Read Response Header
        socket.setSoTimeout(10000);

        LOG.debug("Reading http header");
        StringBuilder response = new StringBuilder();
        boolean foundEnd = false;
        String line;
        while (!foundEnd)
        {
            line = readLine();
            // System.out.printf("RESP: \"%s\"%n",line);
            if (line.length() == 0)
            {
                foundEnd = true;
                LOG.debug("Got full http response header");
            }
            else
            {
                response.append(line).append("\r\n");
            }
        }

        return response.toString();
    }

    public String readLine() throws IOException
    {
        StringBuilder line = new StringBuilder();
        boolean foundCR = false;
        boolean foundLF = false;
        int b;
        while (!(foundCR && foundLF))
        {
            b = inputStream.read();
            Assert.assertThat("Should not have hit EOL (yet) during chunk size read",(int)b,not(-1));
            if (b == 0x0D)
            {
                foundCR = true;
            }
            else if (b == 0x0A)
            {
                foundLF = true;
            }
            else
            {
                foundCR = false;
                foundLF = false;
                line.append((char)b);
            }
        }
        return line.toString();
    }

    public long readChunkSize() throws IOException
    {
        StringBuilder chunkSize = new StringBuilder();
        String validHex = "0123456789ABCDEF";
        boolean foundCR = false;
        boolean foundLF = false;
        int b;
        while (!(foundCR && foundLF))
        {
            b = inputStream.read();
            Assert.assertThat("Should not have hit EOL (yet) during chunk size read",(int)b,not(-1));
            if (b == 0x0D)
            {
                foundCR = true;
            }
            else if (b == 0x0A)
            {
                foundLF = true;
            }
            else
            {
                foundCR = false;
                foundLF = false;
                // Must be valid char
                char c = (char)b;
                if (validHex.indexOf(c) >= 0)
                {
                    chunkSize.append(c);
                }
                else
                {
                    Assert.fail(String.format("Encountered invalid chunk size byte 0x%X",b));
                }
            }
        }
        return Long.parseLong(chunkSize.toString(),16);
    }

    public int readBody(OutputStream stream, int size) throws IOException
    {
        int left = size;
        while (left > 0)
        {
            int val = inputStream.read();
            try
            {
                if (left % 10 == 0)
                    Thread.sleep(1);
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
            if (val == (-1))
            {
                Assert.fail(String.format("Encountered an early EOL (expected another %,d bytes)",left));
            }
            stream.write(val);
            left--;
        }
        return size - left;
    }

    public byte[] readResponseBody(int size) throws IOException
    {
        byte partial[] = new byte[size];
        int readBytes = 0;
        int bytesLeft = size;
        while (readBytes < size)
        {
            int len = inputStream.read(partial,readBytes,bytesLeft);
            Assert.assertThat("Read should not have hit EOL yet",len,not(-1));
            System.out.printf("Read %,d bytes%n",len);
            if (len > 0)
            {
                readBytes += len;
                bytesLeft -= len;
            }
        }
        return partial;
    }

    public OutputStream getOutputStream()
    {
        return outputStream;
    }

    public InputStream getInputStream()
    {
        return inputStream;
    }

    public SocketAddress getEndpoint()
    {
        return endpoint;
    }

    public Socket getSocket()
    {
        return socket;
    }

    public void disconnect() throws IOException
    {
        LOG.debug("disconnect");
        socket.close();
    }

    public int getContentLength(String respHeader)
    {
        Pattern pat = Pattern.compile("Content-Length: ([0-9]*)",Pattern.CASE_INSENSITIVE);
        Matcher mat = pat.matcher(respHeader);
        if (mat.find())
        {
            try
            {
                return Integer.parseInt(mat.group(1));
            }
            catch (NumberFormatException e)
            {
                return -1;
            }
        }
        else
        {
            // Undefined content length
            return -1;
        }
    }

}
