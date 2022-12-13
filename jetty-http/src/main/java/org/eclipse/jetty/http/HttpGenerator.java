//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpTokens.EndOfContent;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http.HttpStatus.INTERNAL_SERVER_ERROR_500;

/**
 * HttpGenerator. Builds HTTP Messages.
 * <p>
 * If the system property "org.eclipse.jetty.http.HttpGenerator.STRICT" is set to true,
 * then the generator will strictly pass on the exact strings received from methods and header
 * fields.  Otherwise a fast case insensitive string lookup is used that may alter the
 * case and white space of some methods/headers
 */
public class HttpGenerator
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpGenerator.class);

    public static final boolean __STRICT = Boolean.getBoolean("org.eclipse.jetty.http.HttpGenerator.STRICT");

    private static final byte[] __colon_space = new byte[]{':', ' '};
    public static final MetaData.Response CONTINUE_100_INFO = new MetaData.Response(HttpVersion.HTTP_1_1, 100, null, null, -1);
    public static final MetaData.Response PROGRESS_102_INFO = new MetaData.Response(HttpVersion.HTTP_1_1, 102, null, null, -1);
    public static final MetaData.Response RESPONSE_500_INFO =
        new MetaData.Response(HttpVersion.HTTP_1_1, INTERNAL_SERVER_ERROR_500, null, HttpFields.build().put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE), 0);

    // states
    public enum State
    {
        START,
        COMMITTED,
        COMPLETING,
        COMPLETING_1XX,
        END
    }

    public enum Result
    {
        NEED_CHUNK,             // Need a small chunk buffer of CHUNK_SIZE
        NEED_INFO,              // Need the request/response metadata info 
        NEED_HEADER,            // Need a buffer to build HTTP headers into
        HEADER_OVERFLOW,        // The header buffer overflowed
        NEED_CHUNK_TRAILER,     // Need a large chunk buffer for last chunk and trailers
        FLUSH,                  // The buffers previously generated should be flushed 
        CONTINUE,               // Continue generating the message
        SHUTDOWN_OUT,           // Need EOF to be signaled
        DONE                    // The current phase of generation is complete
    }

    // other statics
    public static final int CHUNK_SIZE = 12;

    private State _state = State.START;
    private EndOfContent _endOfContent = EndOfContent.UNKNOWN_CONTENT;
    private MetaData _info;

    private long _contentPrepared = 0;
    private boolean _noContentResponse = false;
    private Boolean _persistent = null;

    private final int _send;
    private static final int SEND_SERVER = 0x01;
    private static final int SEND_XPOWEREDBY = 0x02;
    private static final Index<Boolean> ASSUMED_CONTENT_METHODS = new Index.Builder<Boolean>()
        .caseSensitive(false)
        .with(HttpMethod.POST.asString(), Boolean.TRUE)
        .with(HttpMethod.PUT.asString(), Boolean.TRUE)
        .build();

    public static void setJettyVersion(String serverVersion)
    {
        SEND[SEND_SERVER] = StringUtil.getBytes("Server: " + serverVersion + "\r\n");
        SEND[SEND_XPOWEREDBY] = StringUtil.getBytes("X-Powered-By: " + serverVersion + "\r\n");
        SEND[SEND_SERVER | SEND_XPOWEREDBY] = StringUtil.getBytes("Server: " + serverVersion + "\r\nX-Powered-By: " + serverVersion + "\r\n");
    }

    // data
    private boolean _needCRLF = false;

    public HttpGenerator()
    {
        this(false, false);
    }

    public HttpGenerator(boolean sendServerVersion, boolean sendXPoweredBy)
    {
        _send = (sendServerVersion ? SEND_SERVER : 0) | (sendXPoweredBy ? SEND_XPOWEREDBY : 0);
    }

    public void reset()
    {
        _state = State.START;
        _info = null;
        _endOfContent = EndOfContent.UNKNOWN_CONTENT;
        _noContentResponse = false;
        _persistent = null;
        _contentPrepared = 0;
        _needCRLF = false;
    }

    public State getState()
    {
        return _state;
    }

    public boolean isState(State state)
    {
        return _state == state;
    }

    public boolean isIdle()
    {
        return _state == State.START;
    }

    public boolean isEnd()
    {
        return _state == State.END;
    }

    public boolean isCommitted()
    {
        return _state.ordinal() >= State.COMMITTED.ordinal();
    }

    public boolean isChunking()
    {
        return _endOfContent == EndOfContent.CHUNKED_CONTENT;
    }

    public boolean isNoContent()
    {
        return _noContentResponse;
    }

    public void setPersistent(boolean persistent)
    {
        _persistent = persistent;
    }

    /**
     * @return true if known to be persistent
     */
    public boolean isPersistent()
    {
        return Boolean.TRUE.equals(_persistent);
    }

    public boolean isWritten()
    {
        return _contentPrepared > 0;
    }

    public long getContentPrepared()
    {
        return _contentPrepared;
    }

    public void abort()
    {
        _persistent = false;
        _state = State.END;
        _endOfContent = null;
    }

    public Result generateRequest(MetaData.Request info, ByteBuffer header, ByteBuffer chunk, ByteBuffer content, boolean last) throws IOException
    {
        switch (_state)
        {
            case START:
            {
                if (info == null)
                    return Result.NEED_INFO;
                _info = info;

                if (header == null)
                    return Result.NEED_HEADER;

                // prepare the header
                int pos = BufferUtil.flipToFill(header);
                try
                {
                    // generate ResponseLine
                    generateRequestLine(info, header);

                    if (info.getHttpVersion() == HttpVersion.HTTP_0_9)
                        throw new BadMessageException(INTERNAL_SERVER_ERROR_500, "HTTP/0.9 not supported");

                    generateHeaders(header, content, last);

                    boolean expect100 = info.getFields().contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());

                    if (expect100)
                    {
                        _state = State.COMMITTED;
                    }
                    else
                    {
                        // handle the content.
                        int len = BufferUtil.length(content);
                        if (len > 0)
                        {
                            _contentPrepared += len;
                            if (isChunking())
                                prepareChunk(header, len);
                        }
                        _state = last ? State.COMPLETING : State.COMMITTED;
                    }

                    return Result.FLUSH;
                }
                catch (BadMessageException e)
                {
                    throw e;
                }
                catch (BufferOverflowException e)
                {
                    LOG.trace("IGNORED", e);
                    return Result.HEADER_OVERFLOW;
                }
                catch (Exception e)
                {
                    throw new BadMessageException(INTERNAL_SERVER_ERROR_500, e.getMessage(), e);
                }
                finally
                {
                    BufferUtil.flipToFlush(header, pos);
                }
            }

            case COMMITTED:
            {
                return committed(chunk, content, last);
            }

            case COMPLETING:
            {
                return completing(chunk, content);
            }

            case END:
                if (BufferUtil.hasContent(content))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("discarding content in COMPLETING");
                    BufferUtil.clear(content);
                }
                return Result.DONE;

            default:
                throw new IllegalStateException();
        }
    }

    private Result committed(ByteBuffer chunk, ByteBuffer content, boolean last)
    {
        int len = BufferUtil.length(content);

        // handle the content.
        if (len > 0)
        {
            if (isChunking())
            {
                if (chunk == null)
                    return Result.NEED_CHUNK;
                BufferUtil.clearToFill(chunk);
                prepareChunk(chunk, len);
                BufferUtil.flipToFlush(chunk, 0);
            }
            _contentPrepared += len;
        }

        if (last)
        {
            _state = State.COMPLETING;
            return len > 0 ? Result.FLUSH : Result.CONTINUE;
        }
        return len > 0 ? Result.FLUSH : Result.DONE;
    }

    private Result completing(ByteBuffer chunk, ByteBuffer content)
    {
        if (BufferUtil.hasContent(content))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("discarding content in COMPLETING");
            BufferUtil.clear(content);
        }

        if (isChunking())
        {
            if (_info.getTrailerSupplier() != null)
            {
                // Do we need a chunk buffer?
                if (chunk == null || chunk.capacity() <= CHUNK_SIZE)
                    return Result.NEED_CHUNK_TRAILER;

                HttpFields trailers = _info.getTrailerSupplier().get();

                if (trailers != null)
                {
                    // Write the last chunk
                    BufferUtil.clearToFill(chunk);
                    generateTrailers(chunk, trailers);
                    BufferUtil.flipToFlush(chunk, 0);
                    _endOfContent = EndOfContent.UNKNOWN_CONTENT;
                    return Result.FLUSH;
                }
            }

            // Do we need a chunk buffer?
            if (chunk == null)
                return Result.NEED_CHUNK;

            // Write the last chunk
            BufferUtil.clearToFill(chunk);
            prepareChunk(chunk, 0);
            BufferUtil.flipToFlush(chunk, 0);
            _endOfContent = EndOfContent.UNKNOWN_CONTENT;
            return Result.FLUSH;
        }
        _state = State.END;
        return Boolean.TRUE.equals(_persistent) ? Result.DONE : Result.SHUTDOWN_OUT;
    }

    public Result generateResponse(MetaData.Response info, boolean head, ByteBuffer header, ByteBuffer chunk, ByteBuffer content, boolean last) throws IOException
    {
        switch (_state)
        {
            case START:
            {
                if (info == null)
                    return Result.NEED_INFO;
                _info = info;

                HttpVersion version = info.getHttpVersion();
                if (version == null)
                    throw new BadMessageException(INTERNAL_SERVER_ERROR_500, "No version");

                if (version == HttpVersion.HTTP_0_9)
                {
                    _persistent = false;
                    _endOfContent = EndOfContent.EOF_CONTENT;
                    if (BufferUtil.hasContent(content))
                        _contentPrepared += content.remaining();
                    _state = last ? State.COMPLETING : State.COMMITTED;
                    return Result.FLUSH;
                }

                // Do we need a response header
                if (header == null)
                    return Result.NEED_HEADER;

                // prepare the header
                int pos = BufferUtil.flipToFill(header);
                try
                {
                    // generate ResponseLine
                    generateResponseLine(info, header);

                    // Handle 1xx and no content responses
                    int status = info.getStatus();
                    if (HttpStatus.isInformational(status))
                    {
                        _noContentResponse = true;
                        switch (status)
                        {
                            case HttpStatus.SWITCHING_PROTOCOLS_101:
                                break;
                            case HttpStatus.EARLY_HINT_103:
                                generateHeaders(header, content, last);
                                _state = State.COMPLETING_1XX;
                                return Result.FLUSH;
                            default:
                                header.put(HttpTokens.CRLF);
                                _state = State.COMPLETING_1XX;
                                return Result.FLUSH;
                        }
                    }
                    else if (status == HttpStatus.NO_CONTENT_204 || status == HttpStatus.NOT_MODIFIED_304)
                    {
                        _noContentResponse = true;
                    }

                    generateHeaders(header, content, last);

                    // handle the content.
                    int len = BufferUtil.length(content);
                    if (len > 0)
                    {
                        _contentPrepared += len;
                        if (isChunking() && !head)
                            prepareChunk(header, len);
                    }
                    _state = last ? State.COMPLETING : State.COMMITTED;
                }
                catch (BadMessageException e)
                {
                    throw e;
                }
                catch (BufferOverflowException e)
                {
                    LOG.trace("IGNORED", e);
                    return Result.HEADER_OVERFLOW;
                }
                catch (Exception e)
                {
                    throw new BadMessageException(INTERNAL_SERVER_ERROR_500, e.getMessage(), e);
                }
                finally
                {
                    BufferUtil.flipToFlush(header, pos);
                }

                return Result.FLUSH;
            }

            case COMMITTED:
            {
                return committed(chunk, content, last);
            }

            case COMPLETING_1XX:
            {
                reset();
                return Result.DONE;
            }

            case COMPLETING:
            {
                return completing(chunk, content);
            }

            case END:
                if (BufferUtil.hasContent(content))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("discarding content in COMPLETING");
                    BufferUtil.clear(content);
                }
                return Result.DONE;

            default:
                throw new IllegalStateException();
        }
    }

    public void servletUpgrade()
    {
        _noContentResponse = false;
        _state = State.COMMITTED;
    }

    private void prepareChunk(ByteBuffer chunk, int remaining)
    {
        // if we need CRLF add this to header
        if (_needCRLF)
            BufferUtil.putCRLF(chunk);

        // Add the chunk size to the header
        if (remaining > 0)
        {
            BufferUtil.putHexInt(chunk, remaining);
            BufferUtil.putCRLF(chunk);
            _needCRLF = true;
        }
        else
        {
            chunk.put(LAST_CHUNK);
            _needCRLF = false;
        }
    }

    private void generateTrailers(ByteBuffer buffer, HttpFields trailer)
    {
        // if we need CRLF add this to header
        if (_needCRLF)
            BufferUtil.putCRLF(buffer);

        // Add the chunk size to the header
        buffer.put(ZERO_CHUNK);

        int n = trailer.size();
        for (int f = 0; f < n; f++)
        {
            HttpField field = trailer.getField(f);
            putTo(field, buffer);
        }

        BufferUtil.putCRLF(buffer);
    }

    private void generateRequestLine(MetaData.Request request, ByteBuffer header)
    {
        header.put(StringUtil.getBytes(request.getMethod()));
        header.put((byte)' ');
        header.put(StringUtil.getBytes(request.getURIString()));
        header.put((byte)' ');
        header.put(request.getHttpVersion().toBytes());
        header.put(HttpTokens.CRLF);
    }

    private void generateResponseLine(MetaData.Response response, ByteBuffer header)
    {
        // Look for prepared response line
        int status = response.getStatus();
        PreparedResponse preprepared = status < __preprepared.length ? __preprepared[status] : null;
        String reason = response.getReason();
        if (preprepared != null)
        {
            if (reason == null)
                header.put(preprepared._responseLine);
            else
            {
                header.put(preprepared._schemeCode);
                header.put(getReasonBytes(reason));
                header.put(HttpTokens.CRLF);
            }
        }
        else // generate response line
        {
            header.put(HTTP_1_1_SPACE);
            header.put((byte)('0' + status / 100));
            header.put((byte)('0' + (status % 100) / 10));
            header.put((byte)('0' + (status % 10)));
            header.put((byte)' ');
            if (reason == null)
            {
                header.put((byte)('0' + status / 100));
                header.put((byte)('0' + (status % 100) / 10));
                header.put((byte)('0' + (status % 10)));
            }
            else
                header.put(getReasonBytes(reason));
            header.put(HttpTokens.CRLF);
        }
    }

    private byte[] getReasonBytes(String reason)
    {
        if (reason.length() > 1024)
            reason = reason.substring(0, 1024);
        byte[] bytes = StringUtil.getBytes(reason);

        for (int i = bytes.length; i-- > 0; )
        {
            if (bytes[i] == '\r' || bytes[i] == '\n')
                bytes[i] = '?';
        }
        return bytes;
    }

    private void generateHeaders(ByteBuffer header, ByteBuffer content, boolean last)
    {
        final MetaData.Request request = (_info instanceof MetaData.Request) ? (MetaData.Request)_info : null;
        final MetaData.Response response = (_info instanceof MetaData.Response) ? (MetaData.Response)_info : null;

        if (LOG.isDebugEnabled())
        {
            LOG.debug("generateHeaders {} last={} content={}", _info, last, BufferUtil.toDetailString(content));
            LOG.debug(_info.getFields().toString());
        }

        // default field values
        int send = _send;
        HttpField transferEncoding = null;
        boolean http11 = _info.getHttpVersion() == HttpVersion.HTTP_1_1;
        boolean close = false;
        boolean chunkedHint = _info.getTrailerSupplier() != null;
        boolean contentType = false;
        long contentLength = _info.getContentLength();
        boolean contentLengthField = false;

        // Generate fields
        HttpFields fields = _info.getFields();
        if (fields != null)
        {
            int n = fields.size();
            for (int f = 0; f < n; f++)
            {
                HttpField field = fields.getField(f);
                HttpHeader h = field.getHeader();
                if (h == null)
                    putTo(field, header);
                else
                {
                    switch (h)
                    {
                        case CONTENT_LENGTH:
                            if (contentLength < 0)
                                contentLength = field.getLongValue();
                            else if (contentLength != field.getLongValue())
                                throw new BadMessageException(INTERNAL_SERVER_ERROR_500, String.format("Incorrect Content-Length %d!=%d", contentLength, field.getLongValue()));
                            contentLengthField = true;
                            break;

                        case CONTENT_TYPE:
                        {
                            // write the field to the header
                            contentType = true;
                            putTo(field, header);
                            break;
                        }

                        case TRANSFER_ENCODING:
                        {
                            if (http11)
                            {
                                // Don't add yet, treat this only as a hint that there is content
                                // with a preference to chunk if we can
                                transferEncoding = field;
                                chunkedHint = field.contains(HttpHeaderValue.CHUNKED.asString());
                            }
                            break;
                        }

                        case CONNECTION:
                        {
                            boolean keepAlive = field.contains(HttpHeaderValue.KEEP_ALIVE.asString());
                            if (keepAlive && _info.getHttpVersion() == HttpVersion.HTTP_1_0 && _persistent == null)
                            {
                                _persistent = true;
                            }
                            if (field.contains(HttpHeaderValue.CLOSE.asString()))
                            {
                                close = true;
                                _persistent = false;
                            }
                            if (keepAlive && _persistent == Boolean.FALSE)
                            {
                                field = new HttpField(HttpHeader.CONNECTION,
                                    Stream.of(field.getValues()).filter(s -> !HttpHeaderValue.KEEP_ALIVE.is(s))
                                        .collect(Collectors.joining(", ")));
                            }
                            putTo(field, header);
                            break;
                        }

                        case SERVER:
                        {
                            send = send & ~SEND_SERVER;
                            putTo(field, header);
                            break;
                        }

                        default:
                            putTo(field, header);
                    }
                }
            }
        }

        // Can we work out the content length?
        if (last && contentLength < 0 && _info.getTrailerSupplier() == null)
            contentLength = _contentPrepared + BufferUtil.length(content);

        // Calculate how to end _content and connection, _content length and transfer encoding
        // settings from http://tools.ietf.org/html/rfc7230#section-3.3.3

        boolean assumedContentRequest = request != null && ASSUMED_CONTENT_METHODS.get(request.getMethod()) != null;
        boolean assumedContent = assumedContentRequest || contentType || chunkedHint;
        boolean noContentRequest = request != null && contentLength <= 0 && !assumedContent;

        if (_persistent == null)
            _persistent = http11 || (request != null && HttpMethod.CONNECT.is(request.getMethod()));

        // If the message is known not to have content
        if (_noContentResponse || noContentRequest)
        {
            // We don't need to indicate a body length
            _endOfContent = EndOfContent.NO_CONTENT;

            // But it is an error if there actually is content
            if (_contentPrepared > 0)
                throw new BadMessageException(INTERNAL_SERVER_ERROR_500, "Content for no content response");

            if (contentLengthField)
            {
                if (response != null && response.getStatus() == HttpStatus.NOT_MODIFIED_304)
                    putContentLength(header, contentLength);
                else if (contentLength > 0)
                {
                    if (_contentPrepared == 0 && last)
                    {
                        // TODO discard content for backward compatibility with 9.3 releases
                        // TODO review if it is still needed in 9.4 or can we just throw.
                        content.clear();
                    }
                    else
                        throw new BadMessageException(INTERNAL_SERVER_ERROR_500, "Content for no content response");
                }
            }
        }
        // Else if we are HTTP/1.1 and the content length is unknown and we are either persistent
        // or it is a request with content (which cannot EOF) or the app has requested chunking
        else if (http11 && (chunkedHint || contentLength < 0 && (_persistent || assumedContentRequest)))
        {
            // we use chunking
            _endOfContent = EndOfContent.CHUNKED_CONTENT;

            // try to use user supplied encoding as it may have other values.
            if (transferEncoding == null)
                header.put(TRANSFER_ENCODING_CHUNKED);
            else if (transferEncoding.toString().endsWith(HttpHeaderValue.CHUNKED.toString()))
            {
                putTo(transferEncoding, header);
                transferEncoding = null;
            }
            else if (!chunkedHint)
            {
                putTo(new HttpField(HttpHeader.TRANSFER_ENCODING, transferEncoding.getValue() + ",chunked"), header);
                transferEncoding = null;
            }
            else
                throw new BadMessageException(INTERNAL_SERVER_ERROR_500, "Bad Transfer-Encoding");
        }
        // Else if we known the content length and are a request or a persistent response, 
        else if (contentLength >= 0 && (request != null || _persistent))
        {
            // Use the content length 
            _endOfContent = EndOfContent.CONTENT_LENGTH;
            putContentLength(header, contentLength);
        }
        // Else if we are a response
        else if (response != null)
        {
            // We must use EOF - even if we were trying to be persistent
            _endOfContent = EndOfContent.EOF_CONTENT;
            _persistent = false;
            if (contentLength >= 0 && (contentLength > 0 || assumedContent || contentLengthField))
                putContentLength(header, contentLength);

            if (http11 && !close)
                header.put(CONNECTION_CLOSE);
        }
        // Else we must be a request
        else
        {
            // with no way to indicate body length
            throw new BadMessageException(INTERNAL_SERVER_ERROR_500, "Unknown content length for request");
        }

        if (LOG.isDebugEnabled())
            LOG.debug(_endOfContent.toString());

        // Add transfer encoding if it is not chunking
        if (transferEncoding != null)
        {
            if (chunkedHint)
            {
                String v = transferEncoding.getValue();
                int c = v.lastIndexOf(',');
                if (c > 0 && v.lastIndexOf(HttpHeaderValue.CHUNKED.toString(), c) > c)
                    putTo(new HttpField(HttpHeader.TRANSFER_ENCODING, v.substring(0, c).trim()), header);
            }
            else
            {
                putTo(transferEncoding, header);
            }
        }

        // Send server?
        int status = response != null ? response.getStatus() : -1;
        if (status > 199)
            header.put(SEND[send]);

        // end the header.
        header.put(HttpTokens.CRLF);
    }

    private static void putContentLength(ByteBuffer header, long contentLength)
    {
        if (contentLength == 0)
            header.put(CONTENT_LENGTH_0);
        else
        {
            header.put(HttpHeader.CONTENT_LENGTH.getBytesColonSpace());
            BufferUtil.putDecLong(header, contentLength);
            header.put(HttpTokens.CRLF);
        }
    }

    public static byte[] getReasonBuffer(int code)
    {
        PreparedResponse status = code < __preprepared.length ? __preprepared[code] : null;
        if (status != null)
            return status._reason;
        return null;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{s=%s}",
            getClass().getSimpleName(),
            hashCode(),
            _state);
    }

    // common _content
    private static final byte[] ZERO_CHUNK = {(byte)'0', (byte)'\r', (byte)'\n'};
    private static final byte[] LAST_CHUNK = {(byte)'0', (byte)'\r', (byte)'\n', (byte)'\r', (byte)'\n'};
    private static final byte[] CONTENT_LENGTH_0 = StringUtil.getBytes("Content-Length: 0\r\n");
    private static final byte[] CONNECTION_CLOSE = StringUtil.getBytes("Connection: close\r\n");
    private static final byte[] HTTP_1_1_SPACE = StringUtil.getBytes(HttpVersion.HTTP_1_1 + " ");
    private static final byte[] TRANSFER_ENCODING_CHUNKED = StringUtil.getBytes("Transfer-Encoding: chunked\r\n");
    private static final byte[][] SEND = new byte[][]{
        new byte[0],
        StringUtil.getBytes("Server: Jetty(10.x.x)\r\n"),
        StringUtil.getBytes("X-Powered-By: Jetty(10.x.x)\r\n"),
        StringUtil.getBytes("Server: Jetty(10.x.x)\r\nX-Powered-By: Jetty(10.x.x)\r\n")
    };

    // Build cache of response lines for status
    private static class PreparedResponse
    {
        byte[] _reason;
        byte[] _schemeCode;
        byte[] _responseLine;
    }

    private static final PreparedResponse[] __preprepared = new PreparedResponse[HttpStatus.MAX_CODE + 1];

    static
    {
        int versionLength = HttpVersion.HTTP_1_1.toString().length();

        for (int i = 0; i < __preprepared.length; i++)
        {
            HttpStatus.Code code = HttpStatus.getCode(i);
            if (code == null)
                continue;
            String reason = code.getMessage();
            byte[] line = new byte[versionLength + 5 + reason.length() + 2];
            HttpVersion.HTTP_1_1.toBuffer().get(line, 0, versionLength);
            line[versionLength + 0] = ' ';
            line[versionLength + 1] = (byte)('0' + i / 100);
            line[versionLength + 2] = (byte)('0' + (i % 100) / 10);
            line[versionLength + 3] = (byte)('0' + (i % 10));
            line[versionLength + 4] = ' ';
            for (int j = 0; j < reason.length(); j++)
            {
                line[versionLength + 5 + j] = (byte)reason.charAt(j);
            }
            line[versionLength + 5 + reason.length()] = HttpTokens.CARRIAGE_RETURN;
            line[versionLength + 6 + reason.length()] = HttpTokens.LINE_FEED;

            __preprepared[i] = new PreparedResponse();
            __preprepared[i]._schemeCode = Arrays.copyOfRange(line, 0, versionLength + 5);
            __preprepared[i]._reason = Arrays.copyOfRange(line, versionLength + 5, line.length - 2);
            __preprepared[i]._responseLine = line;
        }
    }

    private static void putSanitisedName(String s, ByteBuffer buffer)
    {
        int l = s.length();
        for (int i = 0; i < l; i++)
        {
            char c = s.charAt(i);

            if (c < 0 || c > 0xff || c == '\r' || c == '\n' || c == ':')
                buffer.put((byte)'?');
            else
                buffer.put((byte)(0xff & c));
        }
    }

    private static void putSanitisedValue(String s, ByteBuffer buffer)
    {
        int l = s.length();
        for (int i = 0; i < l; i++)
        {
            char c = s.charAt(i);

            if (c < 0 || c > 0xff || c == '\r' || c == '\n')
                buffer.put((byte)' ');
            else
                buffer.put((byte)(0xff & c));
        }
    }

    public static void putTo(HttpField field, ByteBuffer bufferInFillMode)
    {
        if (field instanceof PreEncodedHttpField)
        {
            ((PreEncodedHttpField)field).putTo(bufferInFillMode, HttpVersion.HTTP_1_0);
        }
        else
        {
            HttpHeader header = field.getHeader();
            if (header != null)
            {
                bufferInFillMode.put(header.getBytesColonSpace());
                putSanitisedValue(field.getValue(), bufferInFillMode);
            }
            else
            {
                putSanitisedName(field.getName(), bufferInFillMode);
                bufferInFillMode.put(__colon_space);
                putSanitisedValue(field.getValue(), bufferInFillMode);
            }

            BufferUtil.putCRLF(bufferInFillMode);
        }
    }

    public static void putTo(HttpFields.Mutable fields, ByteBuffer bufferInFillMode)
    {
        for (HttpField field : fields)
        {
            if (field != null)
                putTo(field, bufferInFillMode);
        }
        BufferUtil.putCRLF(bufferInFillMode);
    }
}
