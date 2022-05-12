//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletInputStream;
import javax.servlet.http.Part;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ByteArrayOutputStream2;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * MultiPartInputStream
 * <p>
 * Handle a MultiPart Mime input stream, breaking it up on the boundary into files and strings.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7578">https://tools.ietf.org/html/rfc7578</a>
 */
public class MultiPartFormInputStream
{
    private static final Logger LOG = Log.getLogger(MultiPartFormInputStream.class);
    private static final MultiMap<Part> EMPTY_MAP = new MultiMap<>(Collections.emptyMap());
    private final MultiMap<Part> _parts;
    private final EnumSet<NonCompliance> _nonComplianceWarnings = EnumSet.noneOf(NonCompliance.class);
    private InputStream _in;
    private MultipartConfigElement _config;
    private String _contentType;
    private Throwable _err;
    private File _tmpDir;
    private File _contextTmpDir;
    private boolean _writeFilesWithFilenames;
    private boolean _parsed;
    private int _bufferSize = 16 * 1024;

    public enum NonCompliance
    {
        TRANSFER_ENCODING("https://tools.ietf.org/html/rfc7578#section-4.7");

        final String _rfcRef;

        NonCompliance(String rfcRef)
        {
            _rfcRef = rfcRef;
        }

        public String getURL()
        {
            return _rfcRef;
        }
    }

    /**
     * @return an EnumSet of non compliances with the RFC that were accepted by this parser
     */
    public EnumSet<NonCompliance> getNonComplianceWarnings()
    {
        return _nonComplianceWarnings;
    }

    public class MultiPart implements Part
    {
        protected String _name;
        protected String _filename;
        protected File _file;
        protected OutputStream _out;
        protected ByteArrayOutputStream2 _bout;
        protected String _contentType;
        protected MultiMap<String> _headers;
        protected long _size = 0;
        protected boolean _temporary = true;

        public MultiPart(String name, String filename)
        {
            _name = name;
            _filename = filename;
        }

        @Override
        public String toString()
        {
            return String.format("Part{n=%s,fn=%s,ct=%s,s=%d,tmp=%b,file=%s}", _name, _filename, _contentType, _size, _temporary, _file);
        }

        protected void setContentType(String contentType)
        {
            _contentType = contentType;
        }

        protected void open() throws IOException
        {
            // We will either be writing to a file, if it has a filename on the content-disposition
            // and otherwise a byte-array-input-stream, OR if we exceed the getFileSizeThreshold, we
            // will need to change to write to a file.
            if (isWriteFilesWithFilenames() && _filename != null && !_filename.trim().isEmpty())
            {
                createFile();
            }
            else
            {
                // Write to a buffer in memory until we discover we've exceed the
                // MultipartConfig fileSizeThreshold
                _out = _bout = new ByteArrayOutputStream2();
            }
        }

        protected void close() throws IOException
        {
            _out.close();
        }

        protected void write(int b) throws IOException
        {
            if (MultiPartFormInputStream.this._config.getMaxFileSize() > 0 && _size + 1 > MultiPartFormInputStream.this._config.getMaxFileSize())
                throw new IllegalStateException("Multipart Mime part " + _name + " exceeds max filesize");

            if (MultiPartFormInputStream.this._config.getFileSizeThreshold() > 0 &&
                _size + 1 > MultiPartFormInputStream.this._config.getFileSizeThreshold() && _file == null)
                createFile();

            _out.write(b);
            _size++;
        }

        protected void write(byte[] bytes, int offset, int length) throws IOException
        {
            if (MultiPartFormInputStream.this._config.getMaxFileSize() > 0 && _size + length > MultiPartFormInputStream.this._config.getMaxFileSize())
                throw new IllegalStateException("Multipart Mime part " + _name + " exceeds max filesize");

            if (MultiPartFormInputStream.this._config.getFileSizeThreshold() > 0 &&
                _size + length > MultiPartFormInputStream.this._config.getFileSizeThreshold() && _file == null)
                createFile();

            _out.write(bytes, offset, length);
            _size += length;
        }

        protected void createFile() throws IOException
        {
            Path parent = MultiPartFormInputStream.this._tmpDir.toPath();
            Path tempFile = Files.createTempFile(parent, "MultiPart", "");
            _file = tempFile.toFile();

            OutputStream fos = Files.newOutputStream(tempFile, StandardOpenOption.WRITE);
            BufferedOutputStream bos = new BufferedOutputStream(fos);

            if (_size > 0 && _out != null)
            {
                // already written some bytes, so need to copy them into the file
                _out.flush();
                _bout.writeTo(bos);
                _out.close();
            }
            _bout = null;
            _out = bos;
        }

        protected void setHeaders(MultiMap<String> headers)
        {
            _headers = headers;
        }

        @Override
        public String getContentType()
        {
            return _contentType;
        }

        @Override
        public String getHeader(String name)
        {
            if (name == null)
                return null;
            return _headers.getValue(StringUtil.asciiToLowerCase(name), 0);
        }

        @Override
        public Collection<String> getHeaderNames()
        {
            return _headers.keySet();
        }

        @Override
        public Collection<String> getHeaders(String name)
        {
            Collection<String> headers = _headers.getValues(name);
            return headers == null ? Collections.emptyList() : headers;
        }

        @Override
        public InputStream getInputStream() throws IOException
        {
            if (_file != null)
            {
                // written to a file, whether temporary or not
                return new BufferedInputStream(new FileInputStream(_file));
            }
            else
            {
                // part content is in memory
                return new ByteArrayInputStream(_bout.getBuf(), 0, _bout.size());
            }
        }

        @Override
        public String getSubmittedFileName()
        {
            return getContentDispositionFilename();
        }

        public byte[] getBytes()
        {
            if (_bout != null)
                return _bout.toByteArray();
            return null;
        }

        @Override
        public String getName()
        {
            return _name;
        }

        @Override
        public long getSize()
        {
            return _size;
        }

        @Override
        public void write(String fileName) throws IOException
        {
            if (_file == null)
            {
                _temporary = false;

                // part data is only in the ByteArrayOutputStream and never been written to disk
                _file = new File(_tmpDir, fileName);

                try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(_file)))
                {
                    _bout.writeTo(bos);
                    bos.flush();
                }
                finally
                {
                    _bout = null;
                }
            }
            else
            {
                // the part data is already written to a temporary file, just rename it
                _temporary = false;

                Path src = _file.toPath();
                Path target = src.resolveSibling(fileName);
                Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
                _file = target.toFile();
            }
        }

        /**
         * Remove the file, whether or not Part.write() was called on it (ie no longer temporary)
         */
        @Override
        public void delete() throws IOException
        {
            if (_file != null && _file.exists())
                if (!_file.delete())
                    throw new IOException("Could Not Delete File");
        }

        /**
         * Only remove tmp files.
         *
         * @throws IOException if unable to delete the file
         */
        public void cleanUp() throws IOException
        {
            if (_temporary)
                delete();
        }

        /**
         * Get the file
         *
         * @return the file, if any, the data has been written to.
         */
        public File getFile()
        {
            return _file;
        }

        /**
         * Get the filename from the content-disposition.
         *
         * @return null or the filename
         */
        public String getContentDispositionFilename()
        {
            return _filename;
        }
    }

    /**
     * @param in Request input stream
     * @param contentType Content-Type header
     * @param config MultipartConfigElement
     * @param contextTmpDir javax.servlet.context.tempdir
     */
    public MultiPartFormInputStream(InputStream in, String contentType, MultipartConfigElement config, File contextTmpDir)
    {
        _contentType = contentType;
        _config = config;
        _contextTmpDir = contextTmpDir;
        if (_contextTmpDir == null)
            _contextTmpDir = new File(System.getProperty("java.io.tmpdir"));

        if (_config == null)
            _config = new MultipartConfigElement(_contextTmpDir.getAbsolutePath());

        MultiMap parts = new MultiMap();

        if (in instanceof ServletInputStream)
        {
            if (((ServletInputStream)in).isFinished())
            {
                parts = EMPTY_MAP;
                _parsed = true;
            }
        }
        if (!_parsed)
            _in = new BufferedInputStream(in);
        _parts = parts;
    }

    /**
     * @return whether the list of parsed parts is empty
     */
    public boolean isEmpty()
    {
        if (_parts == null)
            return true;

        Collection<List<Part>> values = _parts.values();
        for (List<Part> partList : values)
        {
            if (!partList.isEmpty())
                return false;
        }

        return true;
    }

    /**
     * Get the already parsed parts.
     *
     * @return the parts that were parsed
     */
    @Deprecated
    public Collection<Part> getParsedParts()
    {
        if (_parts == null)
            return Collections.emptyList();

        Collection<List<Part>> values = _parts.values();
        List<Part> parts = new ArrayList<>();
        for (List<Part> o : values)
        {
            List<Part> asList = LazyList.getList(o, false);
            parts.addAll(asList);
        }
        return parts;
    }

    /**
     * Delete any tmp storage for parts, and clear out the parts list.
     */
    public void deleteParts()
    {
        MultiException err = null;
        for (List<Part> parts : _parts.values())
        {
            for (Part p : parts)
            {
                try
                {
                    ((MultiPart)p).cleanUp();
                }
                catch (Exception e)
                {
                    if (err == null)
                        err = new MultiException();
                    err.add(e);
                }
            }
        }
        _parts.clear();

        if (err != null)
            err.ifExceptionThrowRuntime();
    }

    /**
     * Parse, if necessary, the multipart data and return the list of Parts.
     *
     * @return the parts
     * @throws IOException if unable to get the parts
     */
    public Collection<Part> getParts() throws IOException
    {
        if (!_parsed)
            parse();
        throwIfError();

        if (_parts.isEmpty())
            return Collections.emptyList();

        Collection<List<Part>> values = _parts.values();
        List<Part> parts = new ArrayList<>();
        for (List<Part> o : values)
        {
            List<Part> asList = LazyList.getList(o, false);
            parts.addAll(asList);
        }
        return parts;
    }

    /**
     * Get the named Part.
     *
     * @param name the part name
     * @return the parts
     * @throws IOException if unable to get the part
     */
    public Part getPart(String name) throws IOException
    {
        if (!_parsed)
            parse();
        throwIfError();
        return _parts.getValue(name, 0);
    }

    /**
     * Throws an exception if one has been latched.
     *
     * @throws IOException the exception (if present)
     */
    protected void throwIfError() throws IOException
    {
        if (_err != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("MultiPart parsing failure ", _err);

            _err.addSuppressed(new Throwable());
            if (_err instanceof IOException)
                throw (IOException)_err;
            if (_err instanceof IllegalStateException)
                throw (IllegalStateException)_err;
            throw new IllegalStateException(_err);
        }
    }

    /**
     * Parse, if necessary, the multipart stream.
     */
    protected void parse()
    {
        // have we already parsed the input?
        if (_parsed)
            return;
        _parsed = true;

        MultiPartParser parser = null;
        Handler handler = new Handler();
        try
        {
            // if its not a multipart request, don't parse it
            if (_contentType == null || !_contentType.startsWith("multipart/form-data"))
                return;

            // sort out the location to which to write the files
            if (_config.getLocation() == null)
                _tmpDir = _contextTmpDir;
            else if ("".equals(_config.getLocation()))
                _tmpDir = _contextTmpDir;
            else
            {
                File f = new File(_config.getLocation());
                if (f.isAbsolute())
                    _tmpDir = f;
                else
                    _tmpDir = new File(_contextTmpDir, _config.getLocation());
            }

            if (!_tmpDir.exists())
                _tmpDir.mkdirs();

            String contentTypeBoundary = "";
            int bstart = _contentType.indexOf("boundary=");
            if (bstart >= 0)
            {
                int bend = _contentType.indexOf(";", bstart);
                bend = (bend < 0 ? _contentType.length() : bend);
                contentTypeBoundary = QuotedStringTokenizer.unquote(value(_contentType.substring(bstart, bend)).trim());
            }

            parser = new MultiPartParser(handler, contentTypeBoundary);
            byte[] data = new byte[_bufferSize];
            int len;
            long total = 0;

            while (true)
            {

                len = _in.read(data);

                if (len > 0)
                {
                    // keep running total of size of bytes read from input and throw an exception if exceeds MultipartConfigElement._maxRequestSize
                    total += len;
                    if (_config.getMaxRequestSize() > 0 && total > _config.getMaxRequestSize())
                    {
                        _err = new IllegalStateException("Request exceeds maxRequestSize (" + _config.getMaxRequestSize() + ")");
                        return;
                    }

                    ByteBuffer buffer = BufferUtil.toBuffer(data);
                    buffer.limit(len);
                    if (parser.parse(buffer, false))
                        break;

                    if (buffer.hasRemaining())
                        throw new IllegalStateException("Buffer did not fully consume");
                }
                else if (len == -1)
                {
                    parser.parse(BufferUtil.EMPTY_BUFFER, true);
                    break;
                }
            }

            // check for exceptions
            if (_err != null)
            {
                return;
            }

            // check we read to the end of the message
            if (parser.getState() != MultiPartParser.State.END)
            {
                if (parser.getState() == MultiPartParser.State.PREAMBLE)
                    _err = new IOException("Missing initial multi part boundary");
                else
                    _err = new IOException("Incomplete Multipart");
            }

            if (LOG.isDebugEnabled())
            {
                LOG.debug("Parsing Complete {} err={}", parser, _err);
            }
        }
        catch (Throwable e)
        {
            _err = e;

            // Notify parser if failure occurs
            if (parser != null)
                parser.parse(BufferUtil.EMPTY_BUFFER, true);
        }
    }

    class Handler implements MultiPartParser.Handler
    {
        private MultiPart _part = null;
        private String contentDisposition = null;
        private String contentType = null;
        private MultiMap<String> headers = new MultiMap<>();

        @Override
        public boolean messageComplete()
        {
            return true;
        }

        @Override
        public void parsedField(String key, String value)
        {
            // Add to headers and mark if one of these fields. //
            headers.put(StringUtil.asciiToLowerCase(key), value);
            if (key.equalsIgnoreCase("content-disposition"))
                contentDisposition = value;
            else if (key.equalsIgnoreCase("content-type"))
                contentType = value;

            // Transfer encoding is not longer considers as it is deprecated as per
            // https://tools.ietf.org/html/rfc7578#section-4.7
            if (key.equalsIgnoreCase("content-transfer-encoding"))
            {
                if (!"8bit".equalsIgnoreCase(value) && !"binary".equalsIgnoreCase(value))
                    _nonComplianceWarnings.add(NonCompliance.TRANSFER_ENCODING);
            }
        }

        @Override
        public boolean headerComplete()
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("headerComplete {}", this);
            }

            try
            {
                // Extract content-disposition
                boolean formData = false;
                if (contentDisposition == null)
                {
                    throw new IOException("Missing content-disposition");
                }

                QuotedStringTokenizer tok = new QuotedStringTokenizer(contentDisposition, ";", false, true);
                String name = null;
                String filename = null;
                while (tok.hasMoreTokens())
                {
                    String t = tok.nextToken().trim();
                    String tl = StringUtil.asciiToLowerCase(t);
                    if (tl.startsWith("form-data"))
                        formData = true;
                    else if (tl.startsWith("name="))
                        name = value(t);
                    else if (tl.startsWith("filename="))
                        filename = filenameValue(t);
                }

                // Check disposition
                if (!formData)
                    throw new IOException("Part not form-data");

                // It is valid for reset and submit buttons to have an empty name.
                // If no name is supplied, the browser skips sending the info for that field.
                // However, if you supply the empty string as the name, the browser sends the
                // field, with name as the empty string. So, only continue this loop if we
                // have not yet seen a name field.
                if (name == null)
                    throw new IOException("No name in part");

                // create the new part
                _part = new MultiPart(name, filename);
                _part.setHeaders(headers);
                _part.setContentType(contentType);
                _parts.add(name, _part);

                try
                {
                    _part.open();
                }
                catch (IOException e)
                {
                    _err = e;
                    return true;
                }
            }
            catch (Exception e)
            {
                _err = e;
                return true;
            }

            return false;
        }

        @Override
        public boolean content(ByteBuffer buffer, boolean last)
        {
            if (_part == null)
                return false;

            if (BufferUtil.hasContent(buffer))
            {
                try
                {
                    _part.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
                }
                catch (IOException e)
                {
                    _err = e;
                    return true;
                }
            }

            if (last)
            {
                try
                {
                    _part.close();
                }
                catch (IOException e)
                {
                    _err = e;
                    return true;
                }
            }

            return false;
        }

        @Override
        public void startPart()
        {
            reset();
        }

        @Override
        public void earlyEOF()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Early EOF {}", MultiPartFormInputStream.this);

            try
            {
                if (_part != null)
                    _part.close();
            }
            catch (IOException e)
            {
                LOG.warn("part could not be closed", e);
            }
        }

        public void reset()
        {
            _part = null;
            contentDisposition = null;
            contentType = null;
            headers = new MultiMap<>();
        }
    }

    /**
     * @deprecated no replacement provided.
     */
    @Deprecated
    public void setDeleteOnExit(boolean deleteOnExit)
    {
        // does nothing.
    }

    public void setWriteFilesWithFilenames(boolean writeFilesWithFilenames)
    {
        _writeFilesWithFilenames = writeFilesWithFilenames;
    }

    public boolean isWriteFilesWithFilenames()
    {
        return _writeFilesWithFilenames;
    }

    /**
     * @deprecated no replacement provided
     */
    @Deprecated
    public boolean isDeleteOnExit()
    {
        return false;
    }

    private static String value(String nameEqualsValue)
    {
        int idx = nameEqualsValue.indexOf('=');
        String value = nameEqualsValue.substring(idx + 1).trim();
        return QuotedStringTokenizer.unquoteOnly(value);
    }

    private static String filenameValue(String nameEqualsValue)
    {
        int idx = nameEqualsValue.indexOf('=');
        String value = nameEqualsValue.substring(idx + 1).trim();

        if (value.matches(".??[a-z,A-Z]\\:\\\\[^\\\\].*"))
        {
            // incorrectly escaped IE filenames that have the whole path
            // we just strip any leading & trailing quotes and leave it as is
            char first = value.charAt(0);
            if (first == '"' || first == '\'')
                value = value.substring(1);
            char last = value.charAt(value.length() - 1);
            if (last == '"' || last == '\'')
                value = value.substring(0, value.length() - 1);

            return value;
        }
        else
            // unquote the string, but allow any backslashes that don't
            // form a valid escape sequence to remain as many browsers
            // even on *nix systems will not escape a filename containing
            // backslashes
            return QuotedStringTokenizer.unquoteOnly(value, true);
    }

    /**
     * @return the size of buffer used to read data from the input stream
     */
    public int getBufferSize()
    {
        return _bufferSize;
    }

    /**
     * @param bufferSize the size of buffer used to read data from the input stream
     */
    public void setBufferSize(int bufferSize)
    {
        _bufferSize = bufferSize;
    }
}
