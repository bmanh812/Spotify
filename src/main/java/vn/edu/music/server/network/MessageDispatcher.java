package vn.edu.music.server.network;

import vn.edu.music.common.protocol.ProtocolMessage;

/** Replace the bootstrap dispatcher with authenticated room handlers after protocol freeze. */
@FunctionalInterface
public interface MessageDispatcher {
    ProtocolMessage dispatch(ProtocolMessage request);
}
