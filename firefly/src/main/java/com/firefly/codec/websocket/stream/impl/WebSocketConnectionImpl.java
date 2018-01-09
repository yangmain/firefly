package com.firefly.codec.websocket.stream.impl;

import com.firefly.codec.common.AbstractConnection;
import com.firefly.codec.common.ConnectionEvent;
import com.firefly.codec.common.ConnectionType;
import com.firefly.codec.http2.model.MetaData;
import com.firefly.codec.http2.stream.HTTP2Configuration;
import com.firefly.codec.websocket.decode.Parser;
import com.firefly.codec.websocket.encode.Generator;
import com.firefly.codec.websocket.frame.*;
import com.firefly.codec.websocket.model.CloseInfo;
import com.firefly.codec.websocket.model.IncomingFrames;
import com.firefly.codec.websocket.model.WebSocketBehavior;
import com.firefly.codec.websocket.stream.IOState;
import com.firefly.codec.websocket.stream.WebSocketConnection;
import com.firefly.codec.websocket.stream.WebSocketPolicy;
import com.firefly.net.ByteBufferOutputEntry;
import com.firefly.net.SecureSession;
import com.firefly.net.Session;
import com.firefly.utils.concurrent.Callback;
import com.firefly.utils.concurrent.Scheduler;
import com.firefly.utils.function.Action1;
import com.firefly.utils.function.Action2;
import com.firefly.utils.io.BufferUtils;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * @author Pengtao Qiu
 */
public class WebSocketConnectionImpl extends AbstractConnection implements WebSocketConnection, IncomingFrames {

    protected final ConnectionEvent<WebSocketConnection> connectionEvent;
    protected IncomingFrames incomingFrames;
    protected final Parser parser;
    protected final Generator generator;
    protected final WebSocketPolicy policy;
    protected final MetaData.Request upgradeRequest;
    protected final MetaData.Response upgradeResponse;
    protected IOState ioState;
    protected final HTTP2Configuration config;

    public WebSocketConnectionImpl(SecureSession secureSession, Session tcpSession,
                                   IncomingFrames incomingFrames, WebSocketPolicy policy,
                                   MetaData.Request upgradeRequest, MetaData.Response upgradeResponse,
                                   HTTP2Configuration config) {
        super(secureSession, tcpSession);

        connectionEvent = new ConnectionEvent<>(this);
        parser = new Parser(policy);
        parser.setIncomingFramesHandler(this);
        generator = new Generator(policy);
        this.policy = policy;
        this.incomingFrames = incomingFrames;
        this.upgradeRequest = upgradeRequest;
        this.upgradeResponse = upgradeResponse;
        this.config = config;
        ioState = new IOState();
        ioState.onOpened();

        if (this.policy.getBehavior() == WebSocketBehavior.CLIENT) {
            Scheduler.Future pingFuture = scheduler.scheduleAtFixedRate(() -> {
                PingFrame pingFrame = new PingFrame();
                outgoingFrame(pingFrame, new Callback() {
                    public void succeeded() {
                        log.info("The websocket connection {} sent ping frame success", getSessionId());
                    }

                    public void failed(Throwable x) {
                        log.warn("the websocket connection {} sends ping frame failure. {}", getSessionId(), x.getMessage());
                    }
                });
            }, config.getWebsocketPingInterval(), config.getWebsocketPingInterval(), TimeUnit.MILLISECONDS);
            onClose(c -> pingFuture.cancel());
        }
    }

    @Override
    public WebSocketConnection onClose(Action1<WebSocketConnection> closedListener) {
        return connectionEvent.onClose(closedListener);
    }

    @Override
    public WebSocketConnection onException(Action2<WebSocketConnection, Throwable> exceptionListener) {
        return connectionEvent.onException(exceptionListener);
    }

    public void notifyClose() {
        connectionEvent.notifyClose();
    }

    public void notifyException(Throwable t) {
        connectionEvent.notifyException(t);
    }

    @Override
    public IOState getIOState() {
        return ioState;
    }

    @Override
    public WebSocketPolicy getPolicy() {
        return policy;
    }

    @Override
    public void outgoingFrame(Frame frame, Callback callback) {
        if (policy.getBehavior() == WebSocketBehavior.CLIENT && frame instanceof WebSocketFrame) {
            WebSocketFrame webSocketFrame = (WebSocketFrame) frame;
            if (!webSocketFrame.isMasked()) {
                webSocketFrame.setMask(generateMask());
            }
        }
        ByteBuffer buf = ByteBuffer.allocate(Generator.MAX_HEADER_LENGTH + frame.getPayloadLength());
        generator.generateWholeFrame(frame, buf);
        BufferUtils.flipToFlush(buf, 0);
        tcpSession.encode(new ByteBufferOutputEntry(callback, buf));
        if (frame.getType() == Frame.Type.CLOSE && frame instanceof CloseFrame) {
            CloseFrame closeFrame = (CloseFrame) frame;
            CloseInfo closeInfo = new CloseInfo(closeFrame.getPayload(), false);
            getIOState().onCloseLocal(closeInfo);
            this.close();
        }
    }

    public void setIncomingFrames(IncomingFrames incomingFrames) {
        this.incomingFrames = incomingFrames;
    }

    @Override
    public void incomingError(Throwable t) {
        Optional.ofNullable(incomingFrames).ifPresent(e -> e.incomingError(t));
    }

    @Override
    public void incomingFrame(Frame frame) {
        switch (frame.getType()) {
            case PING: {
                PongFrame pongFrame = new PongFrame();
                outgoingFrame(pongFrame, Callback.NOOP);
            }
            break;
            case CLOSE: {
                CloseFrame closeFrame = (CloseFrame) frame;
                CloseInfo closeInfo = new CloseInfo(closeFrame.getPayload(), false);
                ioState.onCloseRemote(closeInfo);
                this.close();
            }
            break;
            case PONG: {
                log.info("The websocket connection {} received pong frame", getSessionId());
            }
            break;
        }
        Optional.ofNullable(incomingFrames).ifPresent(e -> e.incomingFrame(frame));
    }

    @Override
    public boolean isEncrypted() {
        return secureSession != null;
    }

    @Override
    public ConnectionType getConnectionType() {
        return ConnectionType.WEB_SOCKET;
    }

    @Override
    public byte[] generateMask() {
        byte[] mask = new byte[4];
        ThreadLocalRandom.current().nextBytes(mask);
        return mask;
    }

    @Override
    public CompletableFuture<Boolean> sendText(String text) {
        TextFrame textFrame = new TextFrame();
        textFrame.setPayload(text);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        outgoingFrame(textFrame, new Callback() {
            @Override
            public void succeeded() {
                future.complete(true);
            }

            @Override
            public void failed(Throwable x) {
                future.completeExceptionally(x);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> sendData(byte[] data) {
        return _sendData(data, BinaryFrame::setPayload);
    }

    @Override
    public CompletableFuture<Boolean> sendData(ByteBuffer data) {
        return _sendData(data, BinaryFrame::setPayload);
    }

    private <T> CompletableFuture<Boolean> _sendData(T data, Action2<BinaryFrame, T> setData) {
        BinaryFrame binaryFrame = new BinaryFrame();
        setData.call(binaryFrame, data);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        outgoingFrame(binaryFrame, new Callback() {
            @Override
            public void succeeded() {
                future.complete(true);
            }

            @Override
            public void failed(Throwable x) {
                future.completeExceptionally(x);
            }
        });
        return future;
    }

    public MetaData.Request getUpgradeRequest() {
        return upgradeRequest;
    }

    public MetaData.Response getUpgradeResponse() {
        return upgradeResponse;
    }

    public Parser getParser() {
        return parser;
    }

    public Generator getGenerator() {
        return generator;
    }
}
