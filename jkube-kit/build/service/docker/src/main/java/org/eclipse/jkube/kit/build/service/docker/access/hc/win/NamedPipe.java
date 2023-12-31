/*
 * Copyright (c) 2019 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at:
 *
 *     https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.jkube.kit.build.service.docker.access.hc.win;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jkube.kit.common.KitLogger;

final class NamedPipe extends Socket {

    private static final CharsetEncoder ASCII_ENCODER = StandardCharsets.US_ASCII.newEncoder();

    private final KitLogger log;
    private final AtomicReference<SocketAddress> socketAddress;

    private final Object connectLock = new Object();
    private volatile boolean inputShutdown;
    private volatile boolean  outputShutdown;

    private String socketPath;

    private RandomAccessFile randomAccessFile;
    private FileChannel channel;

    NamedPipe(KitLogger log) {
        this.log = log;
        this.socketAddress = new AtomicReference<>();
    }

    @Override
    public void connect(SocketAddress endpoint) throws IOException {
        connect(endpoint, 0);
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        if (timeout < 0) {
            throw new IllegalArgumentException("Timeout may not be negative: " + timeout);
        }

        if (!(endpoint instanceof NpipeSocketAddress)) {
            throw new IllegalArgumentException("Unsupported address type: " + endpoint.getClass().getName());
        }

        this.socketAddress.set(endpoint);
        this.socketPath = ((NpipeSocketAddress) endpoint).getPath();

        synchronized (connectLock) {
            randomAccessFile = new RandomAccessFile(socketPath, "rw");
            channel = randomAccessFile.getChannel();
        }
    }

    @Override
    public void bind(SocketAddress bindpoint) throws IOException {
        throw new SocketException("Bind is not supported");
    }

    @Override
    public InetAddress getInetAddress() {
        return null;
    }

    @Override
    public InetAddress getLocalAddress() {
        return null;
    }

    @Override
    public int getPort() {
        return -1;
    }

    @Override
    public int getLocalPort() {
        return -1;
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
        return socketAddress.get();
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return null;
    }

    @Override
    public SocketChannel getChannel() {
        return null;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        if (!channel.isOpen()) {
            throw new SocketException("Socket is closed");
        }

        if (inputShutdown) {
            throw new SocketException("Socket input is shutdown");
        }

        return new FilterInputStream(Channels.newInputStream(channel)) {

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int readed = super.read(b, off, len);
                log.debug("RESPONSE %s", new String(b, off, len, StandardCharsets.UTF_8));
                return readed;
            }

            @Override
            public void close() throws IOException {
                shutdownInput();
            }
        };
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        if (!channel.isOpen()) {
            throw new SocketException("Socket is closed");
        }

        if (outputShutdown) {
            throw new SocketException("Socket output is shutdown");
        }

        return new FilterOutputStream(Channels.newOutputStream(channel)) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if(log.isDebugEnabled()){
                    String request = new String(b, off, len, StandardCharsets.UTF_8);
                    String logValue = isAscii(request) ? request : "not logged due to non-ASCII characters. ";
                    log.debug("REQUEST %s", logValue);
                }
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                shutdownOutput();
            }
        };
    }

    private boolean isAscii(String toCheck) {
        return ASCII_ENCODER.canEncode(toCheck);
    }

    @Override
    public void sendUrgentData(int data) throws IOException {
        throw new SocketException("Urgent data not supported");
    }

    @Override
    public synchronized void setSoTimeout(int soTimeout) {
        // soTimeout is always 0
    }

    @Override
    public synchronized int getSoTimeout() {
        return 0;
    }

    @Override
    public synchronized void setSendBufferSize(int size) throws SocketException {
        if (size <= 0) {
            throw new IllegalArgumentException("Send buffer size must be positive: " + size);
        }
        if (!channel.isOpen()) {
            throw new SocketException("Socket is closed");
        }
    }

    @Override
    public synchronized int getSendBufferSize() throws SocketException {
        if (!channel.isOpen()) {
            throw new SocketException("Socket is closed");
        }

        throw new UnsupportedOperationException("Getting the send buffer size is not supported");
    }

    @Override
    public synchronized void setReceiveBufferSize(int size) throws SocketException {
        if (size <= 0) {
            throw new IllegalArgumentException("Receive buffer size must be positive: " + size);
        }

        if (!channel.isOpen()) {
            throw new SocketException("Socket is closed");
        }

        // just ignore
    }

    @Override
    public synchronized int getReceiveBufferSize() throws SocketException {
        if (!channel.isOpen()) {
            throw new SocketException("Socket is closed");
        }

        throw new UnsupportedOperationException("Getting the receive buffer size is not supported");
    }

    @Override
    public synchronized void setKeepAlive(boolean keepAlive) {
        // keepAlive is always on
    }

    @Override
    public synchronized boolean getKeepAlive() {
        return true;
    }

    @Override
    public void setTrafficClass(int tc) throws SocketException {
        if (tc < 0 || tc > 255) {
            throw new IllegalArgumentException("Traffic class is not in range 0 -- 255: " + tc);
        }

        if (isClosed()) {
            throw new SocketException("Socket is closed");
        }

        // just ignore
    }

    @Override
    public int getTrafficClass() {
        throw new UnsupportedOperationException("Getting the traffic class is not supported");
    }

    @Override
    public void setReuseAddress(boolean on) {
        // just ignore
    }

    @Override
    public boolean getReuseAddress() {
        throw new UnsupportedOperationException("Getting the SO_REUSEADDR option is not supported");
    }

    @Override
    public synchronized void close() throws IOException {
        if (isClosed()) {
            return;
        }
        if (channel != null) {
            channel.close();
        }
        if (randomAccessFile != null) {
            randomAccessFile.close();
        }
        inputShutdown = true;
        outputShutdown = true;
    }

    @Override
    public void shutdownInput() {
        inputShutdown = true;
    }

    @Override
    public void shutdownOutput() {
        outputShutdown = true;
    }

    @Override
    public String toString() {
        if (isConnected()) {
            return "NamedPipe[addr=" + this.socketPath + ']';
        }

        return "NamedPipe[unconnected]";
    }

    @Override
    public boolean isConnected() {
        return !isClosed();
    }

    @Override
    public boolean isBound() {
        return false;
    }

    @Override
    public boolean isClosed() {
        return channel != null && !channel.isOpen();
    }

    @Override
    public boolean isInputShutdown() {
        return inputShutdown;
    }

    @Override
    public boolean isOutputShutdown() {
        return outputShutdown;
    }

    @Override
    public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) {
        // no-op
    }
}
