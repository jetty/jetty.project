package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.eclipse.jetty.client.api.ContentProvider;

public class PathContentProvider implements ContentProvider
{
    private final Path filePath;
    private final long fileSize;
    private final int bufferSize;

    public PathContentProvider(Path filePath) throws IOException
    {
        this(filePath, 4096);
    }

    public PathContentProvider(Path filePath, int bufferSize) throws IOException
    {
        if (!Files.isRegularFile(filePath))
            throw new NoSuchFileException(filePath.toString());
        if (!Files.isReadable(filePath))
            throw new AccessDeniedException(filePath.toString());
        this.filePath = filePath;
        this.fileSize = Files.size(filePath);
        this.bufferSize = bufferSize;
    }

    @Override
    public long length()
    {
        return fileSize;
    }

    @Override
    public Iterator<ByteBuffer> iterator()
    {
        return new LazyIterator();
    }

    private class LazyIterator implements Iterator<ByteBuffer>
    {
        private final ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
        private SeekableByteChannel channel;
        private long position;

        @Override
        public boolean hasNext()
        {
            return position < length();
        }

        @Override
        public ByteBuffer next()
        {
            try
            {
                if (channel == null)
                    channel = Files.newByteChannel(filePath, StandardOpenOption.READ);

                buffer.clear();
                int read = channel.read(buffer);
                if (read < 0)
                    throw new NoSuchElementException();

                position += read;
                buffer.flip();
                return buffer;
            }
            catch (IOException x)
            {
                throw (NoSuchElementException)new NoSuchElementException().initCause(x);
            }
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException();
        }
    }
}
