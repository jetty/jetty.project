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

package org.eclipse.jetty.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.server.MultiParts.NonCompliance;
import org.eclipse.jetty.util.ByteArrayOutputStream2;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.ReadLineInputStream;
import org.eclipse.jetty.util.ReadLineInputStream.Termination;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * MultiPartInputStream
 *
 * Handle a MultiPart Mime input stream, breaking it up on the boundary into files and strings.
 *
 * Non Compliance warnings are documented by the method {@link #getNonComplianceWarnings()}
 *
 * @deprecated Replaced by org.eclipse.jetty.http.MultiPartFormInputStream
 * The code for MultiPartInputStream is slower than its replacement MultiPartFormInputStream. However
 * this class accepts formats non compliant the RFC that the new MultiPartFormInputStream does not accept.
 */
@Deprecated
public class MultiPartInputStreamParser
{
    private static final Logger LOG = Log.getLogger(MultiPartInputStreamParser.class);
    public static final MultipartConfigElement __DEFAULT_MULTIPART_CONFIG = new MultipartConfigElement(System.getProperty("java.io.tmpdir"));
    public static final MultiMap<Part> EMPTY_MAP = new MultiMap(Collections.emptyMap());
    protected InputStream _in;
    protected MultipartConfigElement _config;
    protected String _contentType;
    protected MultiMap<Part> _parts;
    protected Exception _err;
    protected File _tmpDir;
    protected File _contextTmpDir;
    protected boolean _writeFilesWithFilenames;
    protected boolean _parsed;

    private final EnumSet<NonCompliance> nonComplianceWarnings = EnumSet.noneOf(NonCompliance.class);

    /**
     * @return an EnumSet of non compliances with the RFC that were accepted by this parser
     */
    public EnumSet<NonCompliance> getNonComplianceWarnings()
    {
        return nonComplianceWarnings;
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
            throws IOException
        {
            _name = name;
            _filename = filename;
        }

        @Override
        public String toString()
        {
            return String.format("Part{n=%s,fn=%s,ct=%s,s=%d,t=%b,f=%s}", _name, _filename, _contentType, _size, _temporary, _file);
        }

        protected void setContentType(String contentType)
        {
            _contentType = contentType;
        }

        protected void open()
            throws IOException
        {
            //We will either be writing to a file, if it has a filename on the content-disposition
            //and otherwise a byte-array-input-stream, OR if we exceed the getFileSizeThreshold, we
            //will need to change to write to a file.
            if (isWriteFilesWithFilenames() && _filename != null && _filename.trim().length() > 0)
            {
                createFile();
            }
            else
            {
                //Write to a buffer in memory until we discover we've exceed the
                //MultipartConfig fileSizeThreshold
                _out = _bout = new ByteArrayOutputStream2();
            }
        }

        protected void close()
            throws IOException
        {
            _out.close();
        }

        protected void write(int b)
            throws IOException
        {
            if (MultiPartInputStreamParser.this._config.getMaxFileSize() > 0 && _size + 1 > MultiPartInputStreamParser.this._config.getMaxFileSize())
                throw new IllegalStateException("Multipart Mime part " + _name + " exceeds max filesize");

            if (MultiPartInputStreamParser.this._config.getFileSizeThreshold() > 0 && _size + 1 > MultiPartInputStreamParser.this._config.getFileSizeThreshold() && _file == null)
                createFile();

            _out.write(b);
            _size++;
        }

        protected void write(byte[] bytes, int offset, int length)
            throws IOException
        {
            if (MultiPartInputStreamParser.this._config.getMaxFileSize() > 0 && _size + length > MultiPartInputStreamParser.this._config.getMaxFileSize())
                throw new IllegalStateException("Multipart Mime part " + _name + " exceeds max filesize");

            if (MultiPartInputStreamParser.this._config.getFileSizeThreshold() > 0 && _size + length > MultiPartInputStreamParser.this._config.getFileSizeThreshold() && _file == null)
                createFile();

            _out.write(bytes, offset, length);
            _size += length;
        }

        protected void createFile()
            throws IOException
        {
            Path parent = MultiPartInputStreamParser.this._tmpDir.toPath();
            Path tempFile = Files.createTempFile(parent, "MultiPart", "");
            _file = tempFile.toFile();

            OutputStream fos = Files.newOutputStream(tempFile, StandardOpenOption.WRITE);
            BufferedOutputStream bos = new BufferedOutputStream(fos);

            if (_size > 0 && _out != null)
            {
                //already written some bytes, so need to copy them into the file
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

        /**
         * @see Part#getContentType()
         */
        @Override
        public String getContentType()
        {
            return _contentType;
        }

        /**
         * @see Part#getHeader(String)
         */
        @Override
        public String getHeader(String name)
        {
            if (name == null)
                return null;
            return _headers.getValue(name.toLowerCase(Locale.ENGLISH), 0);
        }

        /**
         * @see Part#getHeaderNames()
         */
        @Override
        public Collection<String> getHeaderNames()
        {
            return _headers.keySet();
        }

        /**
         * @see Part#getHeaders(String)
         */
        @Override
        public Collection<String> getHeaders(String name)
        {
            return _headers.getValues(name);
        }

        /**
         * @see Part#getInputStream()
         */
        @Override
        public InputStream getInputStream() throws IOException
        {
            if (_file != null)
            {
                //written to a file, whether temporary or not
                return new BufferedInputStream(new FileInputStream(_file));
            }
            else
            {
                //part content is in memory
                return new ByteArrayInputStream(_bout.getBuf(), 0, _bout.size());
            }
        }

        /**
         * @see Part#getSubmittedFileName()
         */
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

        /**
         * @see Part#getName()
         */
        @Override
        public String getName()
        {
            return _name;
        }

        /**
         * @see Part#getSize()
         */
        @Override
        public long getSize()
        {
            return _size;
        }

        /**
         * @see Part#write(String)
         */
        @Override
        public void write(String fileName) throws IOException
        {
            if (_file == null)
            {
                _temporary = false;

                //part data is only in the ByteArrayOutputStream and never been written to disk
                _file = new File(_tmpDir, fileName);

                BufferedOutputStream bos = null;
                try
                {
                    bos = new BufferedOutputStream(new FileOutputStream(_file));
                    _bout.writeTo(bos);
                    bos.flush();
                }
                finally
                {
                    if (bos != null)
                        bos.close();
                    _bout = null;
                }
            }
            else
            {
                //the part data is already written to a temporary file, just rename it
                _temporary = false;

                Path src = _file.toPath();
                Path target = src.resolveSibling(fileName);
                Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
                _file = target.toFile();
            }
        }

        /**
         * Remove the file, whether or not Part.write() was called on it
         * (ie no longer temporary)
         *
         * @see Part#delete()
         */
        @Override
        public void delete() throws IOException
        {
            if (_file != null && _file.exists())
                _file.delete();
        }

        /**
         * Only remove tmp files.
         *
         * @throws IOException if unable to delete the file
         */
        public void cleanUp() throws IOException
        {
            if (_temporary && _file != null && _file.exists())
                _file.delete();
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
     * @param contextTmpDir jakarta.servlet.context.tempdir
     */
    public MultiPartInputStreamParser(InputStream in, String contentType, MultipartConfigElement config, File contextTmpDir)
    {
        _contentType = contentType;
        _config = config;
        _contextTmpDir = contextTmpDir;
        if (_contextTmpDir == null)
            _contextTmpDir = new File(System.getProperty("java.io.tmpdir"));

        if (_config == null)
            _config = new MultipartConfigElement(_contextTmpDir.getAbsolutePath());

        if (in instanceof ServletInputStream)
        {
            if (((ServletInputStream)in).isFinished())
            {
                _parts = EMPTY_MAP;
                _parsed = true;
                return;
            }
        }
        _in = new ReadLineInputStream(in);
    }

    /**
     * Get the already parsed parts.
     *
     * @return the parts that were parsed
     */
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
        if (!_parsed)
            return;

        Collection<Part> parts = getParsedParts();
        MultiException err = new MultiException();
        for (Part p : parts)
        {
            try
            {
                ((MultiPart)p).cleanUp();
            }
            catch (Exception e)
            {
                err.add(e);
            }
        }
        _parts.clear();

        err.ifExceptionThrowRuntime();
    }

    /**
     * Parse, if necessary, the multipart data and return the list of Parts.
     *
     * @return the parts
     * @throws IOException if unable to get the parts
     */
    public Collection<Part> getParts()
        throws IOException
    {
        if (!_parsed)
            parse();
        throwIfError();

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
    public Part getPart(String name)
        throws IOException
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
    protected void throwIfError()
        throws IOException
    {
        if (_err != null)
        {
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
        //have we already parsed the input?
        if (_parsed)
            return;
        _parsed = true;

        //initialize
        long total = 0; //keep running total of size of bytes read from input and throw an exception if exceeds MultipartConfigElement._maxRequestSize
        _parts = new MultiMap<>();

        //if its not a multipart request, don't parse it
        if (_contentType == null || !_contentType.startsWith("multipart/form-data"))
            return;

        try
        {
            //sort out the location to which to write the files

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

            String boundary = "--" + contentTypeBoundary;
            String lastBoundary = boundary + "--";
            byte[] byteBoundary = lastBoundary.getBytes(StandardCharsets.ISO_8859_1);

            // Get first boundary
            String line = null;
            try
            {
                line = ((ReadLineInputStream)_in).readLine();
            }
            catch (IOException e)
            {
                LOG.warn("Badly formatted multipart request");
                throw e;
            }

            if (line == null)
                throw new IOException("Missing content for multipart request");

            boolean badFormatLogged = false;

            String untrimmed = line;
            line = line.trim();
            while (line != null && !line.equals(boundary) && !line.equals(lastBoundary))
            {
                if (!badFormatLogged)
                {
                    LOG.warn("Badly formatted multipart request");
                    badFormatLogged = true;
                }
                line = ((ReadLineInputStream)_in).readLine();
                untrimmed = line;
                if (line != null)
                    line = line.trim();
            }

            if (line == null || line.length() == 0)
                throw new IOException("Missing initial multi part boundary");

            // Empty multipart.
            if (line.equals(lastBoundary))
                return;

            // check compliance of preamble
            if (Character.isWhitespace(untrimmed.charAt(0)))
                nonComplianceWarnings.add(NonCompliance.NO_CRLF_AFTER_PREAMBLE);

            // Read each part
            boolean lastPart = false;

            outer:
            while (!lastPart)
            {
                String contentDisposition = null;
                String contentType = null;
                String contentTransferEncoding = null;

                MultiMap<String> headers = new MultiMap<>();
                while (true)
                {
                    line = ((ReadLineInputStream)_in).readLine();

                    //No more input
                    if (line == null)
                        break outer;

                    //end of headers:
                    if ("".equals(line))
                        break;

                    total += line.length();
                    if (_config.getMaxRequestSize() > 0 && total > _config.getMaxRequestSize())
                        throw new IllegalStateException("Request exceeds maxRequestSize (" + _config.getMaxRequestSize() + ")");

                    //get content-disposition and content-type
                    int c = line.indexOf(':');
                    if (c > 0)
                    {
                        String key = line.substring(0, c).trim().toLowerCase(Locale.ENGLISH);
                        String value = line.substring(c + 1).trim();
                        headers.put(key, value);
                        if (key.equalsIgnoreCase("content-disposition"))
                            contentDisposition = value;
                        if (key.equalsIgnoreCase("content-type"))
                            contentType = value;
                        if (key.equals("content-transfer-encoding"))
                            contentTransferEncoding = value;
                    }
                }

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
                    String tl = t.toLowerCase(Locale.ENGLISH);
                    if (tl.startsWith("form-data"))
                        formData = true;
                    else if (tl.startsWith("name="))
                        name = value(t);
                    else if (tl.startsWith("filename="))
                        filename = filenameValue(t);
                }

                // Check disposition
                if (!formData)
                {
                    continue;
                }
                //It is valid for reset and submit buttons to have an empty name.
                //If no name is supplied, the browser skips sending the info for that field.
                //However, if you supply the empty string as the name, the browser sends the
                //field, with name as the empty string. So, only continue this loop if we
                //have not yet seen a name field.
                if (name == null)
                {
                    continue;
                }

                //Have a new Part
                MultiPart part = new MultiPart(name, filename);
                part.setHeaders(headers);
                part.setContentType(contentType);
                _parts.add(name, part);
                part.open();

                InputStream partInput = null;
                if ("base64".equalsIgnoreCase(contentTransferEncoding))
                {
                    nonComplianceWarnings.add(NonCompliance.BASE64_TRANSFER_ENCODING);
                    partInput = new Base64InputStream((ReadLineInputStream)_in);
                }
                else if ("quoted-printable".equalsIgnoreCase(contentTransferEncoding))
                {
                    nonComplianceWarnings.add(NonCompliance.QUOTED_PRINTABLE_TRANSFER_ENCODING);
                    partInput = new FilterInputStream(_in)
                    {
                        @Override
                        public int read() throws IOException
                        {
                            int c = in.read();
                            if (c >= 0 && c == '=')
                            {
                                int hi = in.read();
                                int lo = in.read();
                                if (hi < 0 || lo < 0)
                                {
                                    throw new IOException("Unexpected end to quoted-printable byte");
                                }
                                char[] chars = new char[]{(char)hi, (char)lo};
                                c = Integer.parseInt(new String(chars), 16);
                            }
                            return c;
                        }
                    };
                }
                else
                    partInput = _in;

                try
                {
                    int state = -2;
                    int c;
                    boolean cr = false;
                    boolean lf = false;

                    // loop for all lines
                    while (true)
                    {
                        int b = 0;
                        while ((c = (state != -2) ? state : partInput.read()) != -1)
                        {
                            total++;
                            if (_config.getMaxRequestSize() > 0 && total > _config.getMaxRequestSize())
                                throw new IllegalStateException("Request exceeds maxRequestSize (" + _config.getMaxRequestSize() + ")");

                            state = -2;

                            // look for CR and/or LF
                            if (c == 13 || c == 10)
                            {
                                if (c == 13)
                                {
                                    partInput.mark(1);
                                    int tmp = partInput.read();
                                    if (tmp != 10)
                                        partInput.reset();
                                    else
                                        state = tmp;
                                }
                                break;
                            }

                            // Look for boundary
                            if (b >= 0 && b < byteBoundary.length && c == byteBoundary[b])
                            {
                                b++;
                            }
                            else
                            {
                                // Got a character not part of the boundary, so we don't have the boundary marker.
                                // Write out as many chars as we matched, then the char we're looking at.
                                if (cr)
                                    part.write(13);

                                if (lf)
                                    part.write(10);

                                cr = lf = false;
                                if (b > 0)
                                    part.write(byteBoundary, 0, b);

                                b = -1;
                                part.write(c);
                            }
                        }

                        // Check for incomplete boundary match, writing out the chars we matched along the way
                        if ((b > 0 && b < byteBoundary.length - 2) || (b == byteBoundary.length - 1))
                        {
                            if (cr)
                                part.write(13);

                            if (lf)
                                part.write(10);

                            cr = lf = false;
                            part.write(byteBoundary, 0, b);
                            b = -1;
                        }

                        // Boundary match. If we've run out of input or we matched the entire final boundary marker, then this is the last part.
                        if (b > 0 || c == -1)
                        {

                            if (b == byteBoundary.length)
                                lastPart = true;
                            if (state == 10)
                                state = -2;
                            break;
                        }

                        // handle CR LF
                        if (cr)
                            part.write(13);

                        if (lf)
                            part.write(10);

                        cr = (c == 13);
                        lf = (c == 10 || state == 10);
                        if (state == 10)
                            state = -2;
                    }
                }
                finally
                {
                    part.close();
                }
            }
            if (lastPart)
            {
                while (line != null)
                {
                    line = ((ReadLineInputStream)_in).readLine();
                }

                EnumSet<Termination> term = ((ReadLineInputStream)_in).getLineTerminations();

                if (term.contains(Termination.CR))
                    nonComplianceWarnings.add(NonCompliance.CR_LINE_TERMINATION);
                if (term.contains(Termination.LF))
                    nonComplianceWarnings.add(NonCompliance.LF_LINE_TERMINATION);
            }
            else
                throw new IOException("Incomplete parts");
        }
        catch (Exception e)
        {
            _err = e;
        }
    }

    /**
     * @deprecated no replacement offered.
     */
    @Deprecated
    public void setDeleteOnExit(boolean deleteOnExit)
    {
        // does nothing
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
     * @deprecated no replacement offered.
     */
    @Deprecated
    public boolean isDeleteOnExit()
    {
        return false;
    }

    private String value(String nameEqualsValue)
    {
        int idx = nameEqualsValue.indexOf('=');
        String value = nameEqualsValue.substring(idx + 1).trim();
        return QuotedStringTokenizer.unquoteOnly(value);
    }

    private String filenameValue(String nameEqualsValue)
    {
        int idx = nameEqualsValue.indexOf('=');
        String value = nameEqualsValue.substring(idx + 1).trim();

        if (value.matches(".??[a-z,A-Z]\\:\\\\[^\\\\].*"))
        {
            //incorrectly escaped IE filenames that have the whole path
            //we just strip any leading & trailing quotes and leave it as is
            char first = value.charAt(0);
            if (first == '"' || first == '\'')
                value = value.substring(1);
            char last = value.charAt(value.length() - 1);
            if (last == '"' || last == '\'')
                value = value.substring(0, value.length() - 1);

            return value;
        }
        else
            //unquote the string, but allow any backslashes that don't
            //form a valid escape sequence to remain as many browsers
            //even on *nix systems will not escape a filename containing
            //backslashes
            return QuotedStringTokenizer.unquoteOnly(value, true);
    }

    // TODO: considers switching to Base64.getMimeDecoder().wrap(InputStream)
    private static class Base64InputStream extends InputStream
    {
        ReadLineInputStream _in;
        String _line;
        byte[] _buffer;
        int _pos;
        Base64.Decoder base64Decoder = Base64.getDecoder();

        public Base64InputStream(ReadLineInputStream rlis)
        {
            _in = rlis;
        }

        @Override
        public int read() throws IOException
        {
            if (_buffer == null || _pos >= _buffer.length)
            {
                //Any CR and LF will be consumed by the readLine() call.
                //We need to put them back into the bytes returned from this
                //method because the parsing of the multipart content uses them
                //as markers to determine when we've reached the end of a part.
                _line = _in.readLine();
                if (_line == null)
                    return -1;  //nothing left
                if (_line.startsWith("--"))
                    _buffer = (_line + "\r\n").getBytes(); //boundary marking end of part
                else if (_line.length() == 0)
                    _buffer = "\r\n".getBytes(); //blank line
                else
                {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream((4 * _line.length() / 3) + 2);
                    baos.write(base64Decoder.decode(_line));
                    baos.write(13);
                    baos.write(10);
                    _buffer = baos.toByteArray();
                }

                _pos = 0;
            }

            return _buffer[_pos++];
        }
    }
}
