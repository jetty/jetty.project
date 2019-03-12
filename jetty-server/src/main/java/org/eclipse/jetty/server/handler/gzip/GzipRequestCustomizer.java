package org.eclipse.jetty.server.handler.gzip;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.regex.Pattern;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.BufferUtil;

public class GzipRequestCustomizer implements HttpConfiguration.Customizer
{
    public static final String GZIP = "gzip";
    private static final HttpField X_CE_GZIP = new HttpField("X-Content-Encoding","gzip");
    private static final Pattern COMMA_GZIP = Pattern.compile(".*, *gzip");

    private final ByteBufferPool buffers = new ArrayByteBufferPool();  // TODO Configure
    private int compressedBufferSize = 4*1024; // TODO configure
    private int inflatedBufferSize = 16*1024; // TODO configure
    @Override
    public void customize(Connector connector, HttpConfiguration channelConfig, Request request)
    {
        try
        {
            HttpFields fields = request.getHttpFields();
            String content_encoding = fields.get(HttpHeader.CONTENT_ENCODING);
            if (content_encoding == null)
                return;

            if (content_encoding.equalsIgnoreCase("gzip"))
            {
                fields.remove(HttpHeader.CONTENT_ENCODING);
            }
            else if (COMMA_GZIP.matcher(content_encoding).matches())
            {
                fields.remove(HttpHeader.CONTENT_ENCODING);
                fields.add(HttpHeader.CONTENT_ENCODING, content_encoding.substring(0, content_encoding.lastIndexOf(',')));
            }
            else
            {
                return;
            }

            fields.add(X_CE_GZIP);

            // Read all the compressed content into a queue of buffers
            final HttpInput input = request.getHttpInput();
            Queue<ByteBuffer> compressed = new ArrayQueue<>();
            ByteBuffer buffer = null;
            while (true)
            {
                if (buffer==null || BufferUtil.isFull(buffer))
                {
                    buffer = buffers.acquire(compressedBufferSize,false);
                    compressed.add(buffer);
                }
                int l = input.read(buffer.array(), buffer.arrayOffset()+buffer.limit(), BufferUtil.space(buffer));
                if (l<0)
                    break;
                buffer.limit(buffer.limit()+l);
            }
            input.recycle();


            // Handle no content
            if (compressed.size()==1 && BufferUtil.isEmpty(buffer))
            {
                input.eof();
                return;
            }

            // TODO Perhaps pool docoders/inflators?
            GZIPContentDecoder decoder = new GZIPContentDecoder(buffers, inflatedBufferSize)
            {
                @Override
                protected boolean decodedChunk(ByteBuffer chunk)
                {
                    super.decodedChunk(chunk);
                    return false;
                }
            };

            input.addContent(new InflatingContent(input, decoder,compressed));

        }
        catch(Throwable t)
        {
            throw new BadMessageException(400,"Bad compressed request",t);
        }
    }

    private ByteBuffer inflate(GZIPContentDecoder decoder, Queue<ByteBuffer> compressed)
    {
        while (!compressed.isEmpty() && BufferUtil.isEmpty(compressed.peek()))
            buffers.release(compressed.poll());

        if (compressed.isEmpty())
            return BufferUtil.EMPTY_BUFFER;

        ByteBuffer inflated = decoder.decode(compressed.peek());
        System.err.println(BufferUtil.toDetailString(inflated));
        return inflated;
    }


    private class InflatingContent extends HttpInput.Content
    {
        final HttpInput input;
        final GZIPContentDecoder decoder;
        final Queue<ByteBuffer> compressed;

        public InflatingContent(HttpInput input, GZIPContentDecoder decoder, Queue<ByteBuffer> compressed)
        {
            super(inflate(decoder,compressed));
            this.input = input;
            this.decoder = decoder;
            this.compressed = compressed;
        }

        @Override
        public void succeeded()
        {
            if (decoder.isFinished() && compressed.isEmpty())
                input.eof();
            else
                input.addContent(new InflatingContent(input,decoder,compressed));
        }

        @Override
        public void failed(Throwable x)
        {
            input.failed(x);
        }
    }

}
