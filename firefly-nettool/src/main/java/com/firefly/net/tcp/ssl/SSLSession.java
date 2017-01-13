package com.firefly.net.tcp.ssl;

import com.firefly.net.SSLContextFactory;
import com.firefly.net.SSLEventHandler;
import com.firefly.net.Session;
import com.firefly.net.buffer.FileRegion;
import com.firefly.utils.concurrent.Callback;
import com.firefly.utils.concurrent.CountingCallback;
import com.firefly.utils.io.BufferReaderHandler;
import org.eclipse.jetty.alpn.ALPN;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

public class SSLSession implements Closeable {

    protected static final Logger log = LoggerFactory.getLogger("firefly-system");

    private static final boolean debugMode = Boolean.getBoolean("debugMode");

    private final Session session;
    private final SSLEngine sslEngine;

    private ByteBuffer inNetBuffer;
    private ByteBuffer requestBuffer;

    private static final int requestBufferSize = 1024 * 8;
    private static final int writeBufferSize = 1024 * 8;

    /*
     * An empty ByteBuffer for use when one isn't available, say as a source
     * buffer during initial handshake wraps or for close operations.
     */
    private static final ByteBuffer hsBuffer = ByteBuffer.allocate(0);

    /*
     * We have received the shutdown request by our caller, and have closed our
     * outbound side.
     */
    private boolean closed = false;

    /*
     * During our initial handshake, keep track of the next SSLEngine operation
     * that needs to occur:
     *
     * NEED_WRAP/NEED_UNWRAP
     *
     * Once the initial handshake has completed, we can short circuit handshake
     * checks with initialHSComplete.
     */
    private HandshakeStatus initialHSStatus;

    private boolean initialHSComplete;

    private final SSLEventHandler sslEventHandler;

    public SSLSession(SSLContextFactory factory, boolean clientMode, Session session, SSLEventHandler sslEventHandler)
            throws Throwable {
        this(factory.createSSLEngine(clientMode), session, sslEventHandler, null);
    }

    public SSLSession(SSLEngine sslEngine, Session session,
                      SSLEventHandler sslEventHandler, ALPN.Provider provider) throws Throwable {
        this.session = session;
        requestBuffer = ByteBuffer.allocate(requestBufferSize);
        initialHSComplete = false;
        this.sslEventHandler = sslEventHandler;

        if (provider != null) {
            ALPN.debug = debugMode;
            ALPN.put(sslEngine, provider);
        }

        this.sslEngine = sslEngine;
        this.sslEngine.beginHandshake();
        initialHSStatus = sslEngine.getHandshakeStatus();
        if (sslEngine.getUseClientMode()) {
            doHandshakeResponse();
        }
    }

    /**
     * The initial handshake is a procedure by which the two peers exchange
     * communication parameters until an SSLSession is established. Application
     * data can not be sent during this phase.
     *
     * @param receiveBuffer Encrypted message
     * @return True means handshake success
     * @throws Throwable A runtime exception
     */
    private boolean doHandshake(ByteBuffer receiveBuffer) throws Throwable {
        if (!session.isOpen()) {
            sslEngine.closeInbound();
            return (initialHSComplete = false);
        }

        if (initialHSComplete)
            return true;

        if (initialHSStatus == HandshakeStatus.FINISHED) {
            log.info("session {} handshake success!", session.getSessionId());
            initialHSComplete = true;
            sslEventHandler.handshakeFinished(this);
            return initialHSComplete;
        }

        switch (initialHSStatus) {
            case NEED_UNWRAP:
                doHandshakeReceive(receiveBuffer);
                if (initialHSStatus != HandshakeStatus.NEED_WRAP)
                    break;

            case NEED_WRAP:
                doHandshakeResponse();
                break;

            default: // NOT_HANDSHAKING/NEED_TASK/FINISHED
                throw new RuntimeException("Invalid Handshaking State" + initialHSStatus);
        }
        return initialHSComplete;
    }

    private void doHandshakeReceive(ByteBuffer receiveBuffer) throws Throwable {
        SSLEngineResult result;

        merge(receiveBuffer);

        needIO:
        while (initialHSStatus == HandshakeStatus.NEED_UNWRAP) {

            unwrap:
            while (true) {
                result = sslEngine.unwrap(inNetBuffer, requestBuffer);
                initialHSStatus = result.getHandshakeStatus();
                if (log.isDebugEnabled()) {
                    log.debug("session {} handshake receives data, init: {} | ret: {} | complete: {} ",
                            session.getSessionId(), initialHSStatus, result.getStatus(), initialHSComplete);
                }
                switch (result.getStatus()) {
                    case OK:
                        switch (initialHSStatus) {
                            case NOT_HANDSHAKING:
                                throw new IOException("Not handshaking during initial handshake");

                            case NEED_TASK:
                                initialHSStatus = doTasks();
                                break;

                            case FINISHED:
                                log.info("session {} handshake success!", session.getSessionId());
                                initialHSComplete = true;
                                sslEventHandler.handshakeFinished(this);
                                break needIO;
                            default:
                                break;
                        }
                        break unwrap;

                    case BUFFER_UNDERFLOW:
                        break needIO;

                    case BUFFER_OVERFLOW:
                        // Reset the application buffer size.
                        int appSize = sslEngine.getSession().getApplicationBufferSize();
                        ByteBuffer b = ByteBuffer.allocate(appSize + requestBuffer.position());
                        requestBuffer.flip();
                        b.put(requestBuffer);
                        requestBuffer = b;
                        // retry the operation.
                        break;

                    default: // CLOSED:
                        throw new IOException("Received" + result.getStatus() + "during initial handshaking");
                }
            }
        } // "needIO" block.
    }

    private void doHandshakeResponse() throws Throwable {
        while (initialHSStatus == HandshakeStatus.NEED_WRAP) {
            SSLEngineResult result;
            ByteBuffer writeBuf = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());

            wrap:
            while (true) {
                result = sslEngine.wrap(hsBuffer, writeBuf);
                initialHSStatus = result.getHandshakeStatus();
                if (log.isDebugEnabled()) {
                    log.debug("session {} handshake response, init: {} | ret: {} | complete: {} ",
                            session.getSessionId(), initialHSStatus, result.getStatus(), initialHSComplete);
                }
                switch (result.getStatus()) {
                    case OK:
                        if (initialHSStatus == HandshakeStatus.NEED_TASK) {
                            initialHSStatus = doTasks();
                        }

                        writeBuf.flip();
                        session.write(writeBuf, Callback.NOOP);
                        break wrap;

                    case BUFFER_OVERFLOW:
                        int netSize = sslEngine.getSession().getPacketBufferSize();
                        ByteBuffer b = ByteBuffer.allocate(writeBuf.position() + netSize);
                        writeBuf.flip();
                        b.put(writeBuf);
                        writeBuf = b;
                        break;

                    default: // BUFFER_UNDERFLOW, CLOSED:
                        throw new IOException("Received " + result.getStatus() + " during initial handshaking");
                }
            }
        }
    }

    private void merge(ByteBuffer now) {
        if (!now.hasRemaining())
            return;

        if (inNetBuffer != null) {
            if (inNetBuffer.hasRemaining()) {
                ByteBuffer ret = ByteBuffer.allocate(inNetBuffer.remaining() + now.remaining());
                ret.put(inNetBuffer).put(now).flip();
                inNetBuffer = ret;
            } else {
                inNetBuffer = now;
            }
        } else {
            inNetBuffer = now;
        }
    }

    private ByteBuffer getRequestBuffer() {
        requestBuffer.flip();
        ByteBuffer buf = ByteBuffer.allocate(requestBuffer.remaining());
        buf.put(requestBuffer).flip();
        requestBuffer = ByteBuffer.allocate(requestBufferSize);
        return buf;
    }

    /**
     * Do all the outstanding handshake tasks in the current Thread.
     *
     * @return The result of handshake
     */
    private SSLEngineResult.HandshakeStatus doTasks() {
        Runnable runnable;

        // We could run this in a separate thread, but do in the current for
        // now.
        while ((runnable = sslEngine.getDelegatedTask()) != null) {
            runnable.run();
        }
        return sslEngine.getHandshakeStatus();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            // log.debug("close SSL engine, {}|{}", sslEngine.isInboundDone(),
            // sslEngine.isOutboundDone());
            sslEngine.closeOutbound();
            closed = true;
        }
    }

    public boolean isOpen() {
        return !closed;
    }

    /**
     * This method is used to decrypt data, it implied do handshake
     *
     * @param receiveBuffer Encrypted message
     * @return plaintext
     * @throws Throwable sslEngine error during data read
     */
    public ByteBuffer read(ByteBuffer receiveBuffer) throws Throwable {
        if (!doHandshake(receiveBuffer))
            return null;

        if (!initialHSComplete)
            throw new IllegalStateException("The initial handshake is not complete.");

        if (log.isDebugEnabled()) {
            log.debug("SSL read current session {} status -> {}", session.getSessionId(), session.isOpen());
        }
        merge(receiveBuffer);
        if (!inNetBuffer.hasRemaining())
            return null;

        SSLEngineResult result;

        while (true) {
            result = sslEngine.unwrap(inNetBuffer, requestBuffer);

			/*
             * Could check here for a renegotation, but we're only doing a
			 * simple read/write, and won't have enough state transitions to do
			 * a complete handshake, so ignore that possibility.
			 */
            switch (result.getStatus()) {

                case BUFFER_OVERFLOW:
                    // Reset the application buffer size.
                    int appSize = sslEngine.getSession().getApplicationBufferSize();
                    ByteBuffer b = ByteBuffer.allocate(appSize + requestBuffer.position());
                    requestBuffer.flip();
                    b.put(requestBuffer);
                    requestBuffer = b;
                    // retry the operation.
                    break;

                case BUFFER_UNDERFLOW:
                    return null;

                case OK:
                    if (result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
                        doTasks();
                    }
                    if (!inNetBuffer.hasRemaining())
                        return getRequestBuffer();

                    break;

                default:
                    throw new IOException("sslEngine error during data read: " + result.getStatus());
            }
        }
    }

    public int write(ByteBuffer[] outputBuffers, Callback callback) throws Throwable {
        int ret = 0;
        CountingCallback countingCallback = new CountingCallback(callback, outputBuffers.length);
        for (ByteBuffer outputBuffer : outputBuffers) {
            ret += write(outputBuffer, countingCallback);
        }
        return ret;
    }

    /**
     * This method is used to encrypt and flush to socket channel
     *
     * @param outputBuffer Plaintext message
     * @return writen length
     * @throws Throwable sslEngine error during data write
     */
    public int write(ByteBuffer outputBuffer, Callback callback) throws Throwable {
        if (!initialHSComplete)
            throw new IllegalStateException("The initial handshake is not complete.");

        int ret = 0;
        if (!outputBuffer.hasRemaining())
            return ret;

        final int remain = outputBuffer.remaining();

        while (ret < remain) {
            ByteBuffer writeBuf = ByteBuffer.allocate(writeBufferSize);

            wrap:
            while (true) {
                SSLEngineResult result = sslEngine.wrap(outputBuffer, writeBuf);
                ret += result.bytesConsumed();

                switch (result.getStatus()) {
                    case OK:
                        if (result.getHandshakeStatus() == HandshakeStatus.NEED_TASK)
                            doTasks();

                        writeBuf.flip();
                        session.write(writeBuf, callback);
                        break wrap;

                    case BUFFER_OVERFLOW:
                        int netSize = sslEngine.getSession().getPacketBufferSize();
                        ByteBuffer b = ByteBuffer.allocate(writeBuf.position() + netSize);
                        writeBuf.flip();
                        b.put(writeBuf);
                        writeBuf = b;
                        // retry the operation.
                        break;
                    default:
                        throw new IOException("sslEngine error during data write: " + result.getStatus());
                }
            }
        }

        return ret;
    }

    private class FileBufferReaderHandler implements BufferReaderHandler {

        private final long len;

        private FileBufferReaderHandler(long len) {
            this.len = len;
        }

        @Override
        public void readBuffer(ByteBuffer buf, CountingCallback countingCallback, long count) {
            log.debug("write file,  count: {} , lenth: {}", count, len);
            try {
                write(buf, countingCallback);
            } catch (Throwable e) {
                log.error("ssl session writing error", e);
            }
        }

    }

    public long transferFileRegion(FileRegion file, Callback callback) throws Throwable {
        long ret = 0;
        try (FileRegion fileRegion = file) {
            fileRegion.transferTo(callback, new FileBufferReaderHandler(file.getLength()));
        }
        return ret;
    }

    public boolean isHandshakeFinished() {
        return initialHSComplete;
    }
}
