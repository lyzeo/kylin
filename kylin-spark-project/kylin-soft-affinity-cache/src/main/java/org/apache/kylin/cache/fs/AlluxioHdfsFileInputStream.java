/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kylin.cache.fs;

import alluxio.AlluxioURI;
import alluxio.client.file.FileInStream;
import alluxio.client.file.FileSystem;
import alluxio.exception.AlluxioException;
import alluxio.exception.ExceptionMessage;
import alluxio.exception.FileDoesNotExistException;

import org.apache.hadoop.fs.ByteBufferReadable;
import org.apache.hadoop.fs.FileSystem.Statistics;
import org.apache.hadoop.fs.PositionedReadable;
import org.apache.hadoop.fs.Seekable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import javax.annotation.concurrent.NotThreadSafe;

@NotThreadSafe
public class AlluxioHdfsFileInputStream extends InputStream implements Seekable, PositionedReadable,
        ByteBufferReadable {
    private static final Logger LOG = LoggerFactory.getLogger(AlluxioHdfsFileInputStream.class);

    private final Statistics mStatistics;
    private final FileInStream mInputStream;

    private boolean mClosed = false;

    /**
     * Constructs a new stream for reading a file from HDFS.
     *
     * @param fs the file system
     * @param uri the Alluxio file URI
     * @param stats filesystem statistics
     */
    public AlluxioHdfsFileInputStream(FileSystem fs, AlluxioURI uri, Statistics stats)
            throws IOException {
        LOG.debug("HdfsFileInputStream({}, {})", uri, stats);

        mStatistics = stats;
        try {
            mInputStream = fs.openFile(uri);
        } catch (FileDoesNotExistException e) {
            // Transform the Alluxio exception to a Java exception to satisfy the HDFS API contract.
            throw new FileNotFoundException(ExceptionMessage.PATH_DOES_NOT_EXIST.getMessage(uri));
        } catch (AlluxioException e) {
            throw new IOException(e);
        }
    }

    /**
     * Constructs a new stream for reading a file from HDFS.
     *
     * @param inputStream the input stream
     * @param stats filesystem statistics
     */
    public AlluxioHdfsFileInputStream(FileInStream inputStream, Statistics stats) {
        mInputStream = inputStream;
        mStatistics = stats;
    }

    @Override
    public int available() throws IOException {
        if (mClosed) {
            throw new IOException("Cannot query available bytes from a closed stream.");
        }
        return (int) mInputStream.remaining();
    }

    @Override
    public void close() throws IOException {
        if (mClosed) {
            return;
        }
        mInputStream.close();
        mClosed = true;
    }

    @Override
    public long getPos() throws IOException {
        return mInputStream.getPos();
    }

    @Override
    public int read() throws IOException {
        if (mClosed) {
            throw new IOException(ExceptionMessage.READ_CLOSED_STREAM.getMessage());
        }

        int data = mInputStream.read();
        if (data != -1 && mStatistics != null) {
            mStatistics.incrementBytesRead(1);
            LOG.info("Read one byte.");
        }
        return data;
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        return read(buffer, 0, buffer.length);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (mClosed) {
            throw new IOException(ExceptionMessage.READ_CLOSED_STREAM.getMessage());
        }

        int bytesRead = mInputStream.read(buffer, offset, length);
        if (bytesRead != -1 && mStatistics != null) {
            mStatistics.incrementBytesRead(bytesRead);
            LOG.info("Read {} bytes.", bytesRead);
        }
        return bytesRead;
    }

    @Override
    public int read(ByteBuffer buf) throws IOException {
        if (mClosed) {
            throw new IOException(ExceptionMessage.READ_CLOSED_STREAM.getMessage());
        }
        int bytesRead;
        if (buf.hasArray() || !buf.isDirect()) {
            bytesRead = mInputStream.read(buf.array(), buf.position(), buf.remaining());
            if (bytesRead > 0) {
                buf.position(buf.position() + bytesRead);
            }
        } else {
            bytesRead = mInputStream.read(buf);
        }
        if (bytesRead != -1 && mStatistics != null) {
            mStatistics.incrementBytesRead(bytesRead);
            LOG.info("Read {} byte buffer {}.", bytesRead, buf.hasArray());
        }
        return bytesRead;
    }

    @Override
    public int read(long position, byte[] buffer, int offset, int length) throws IOException {
        if (mClosed) {
            throw new IOException(ExceptionMessage.READ_CLOSED_STREAM.getMessage());
        }

        int bytesRead = mInputStream.positionedRead(position, buffer, offset, length);
        if (bytesRead != -1 && mStatistics != null) {
            mStatistics.incrementBytesRead(bytesRead);
            LOG.info("Read {} {} byte buffer.", position, bytesRead);
        }
        return bytesRead;
    }

    @Override
    public void readFully(long position, byte[] buffer) throws IOException {
        readFully(position, buffer, 0, buffer.length);
    }

    @Override
    public void readFully(long position, byte[] buffer, int offset, int length) throws IOException {
        int totalBytesRead = 0;
        while (totalBytesRead < length) {
            int bytesRead =
                    read(position + totalBytesRead, buffer, offset + totalBytesRead, length - totalBytesRead);
            if (bytesRead == -1) {
                throw new EOFException();
            }
            totalBytesRead += bytesRead;
        }
        LOG.info("Read fully {} {} byte buffer.", position, totalBytesRead);
    }

    @Override
    public void seek(long pos) throws IOException {
        try {
            mInputStream.seek(pos);
        } catch (IllegalArgumentException e) { // convert back to IOException
            throw new IOException(e);
        }
    }

    /**
     * This method is not supported in {@link HdfsFileInputStream}.
     *
     * @param targetPos N/A
     * @return N/A
     * @throws IOException always
     */
    @Override
    public boolean seekToNewSource(long targetPos) throws IOException {
        throw new IOException(ExceptionMessage.NOT_SUPPORTED.getMessage());
    }

    @Override
    public long skip(long n) throws IOException {
        if (mClosed) {
            throw new IOException("Cannot skip bytes in a closed stream.");
        }
        return mInputStream.skip(n);
    }
}