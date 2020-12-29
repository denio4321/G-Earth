package gearth.protocol.connection.proxy.unity;

import gearth.protocol.HConnection;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import gearth.protocol.connection.*;
import gearth.protocol.connection.proxy.ProxyProvider;
import gearth.protocol.packethandler.unity.UnityPacketHandler;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@ServerEndpoint(value = "/packethandler")
public class UnityCommunicator {

    private final HProxySetter proxySetter;
    private final HStateSetter stateSetter;
    private final HConnection hConnection;
    private final ProxyProvider proxyProvider;

    private HProxy hProxy = null;
    private String allowedSession = null;
    private String revision = null;

    public UnityCommunicator(HProxySetter proxySetter, HStateSetter stateSetter, HConnection hConnection, ProxyProvider proxyProvider) {
        this.proxySetter = proxySetter;
        this.stateSetter = stateSetter;
        this.hConnection = hConnection;
        this.proxyProvider = proxyProvider;
    }


    @OnOpen
    public void onOpen(Session session) throws IOException {
        session.setMaxBinaryMessageBufferSize(1024 * 1024 * 10);
    }

    private int lastType = 2; // 0 = incoming, 1 = outgoing, 2 = expect new

    @OnMessage
    public void onMessage(byte[] b, boolean isLast, Session session) throws IOException {
        if (allowedSession != null && !session.getId().equals(allowedSession)) {
            return;
        }

        if (revision == null) {
            revision = new String(b, StandardCharsets.ISO_8859_1);
            allowedSession = session.getId();
            if (!isLast) {
                System.out.println("this is bad");
            }
            return;
        }



        byte[] packet = lastType == 2 ? Arrays.copyOfRange(b, 1, b.length) : b;

        if (hProxy == null && b[0] == 1) {
            HPacket maybe = new HPacket(packet);
            if (maybe.getBytesLength() > 6 && maybe.headerId() == 4000) {
                hProxy = new HProxy(HClient.UNITY, "", "", -1, -1, "");
                hProxy.verifyProxy(
                        new UnityPacketHandler(hConnection.getExtensionHandler(), hConnection.getTrafficObservables(), session, HMessage.Direction.TOCLIENT),
                        new UnityPacketHandler(hConnection.getExtensionHandler(), hConnection.getTrafficObservables(), session, HMessage.Direction.TOSERVER),
                        revision
                );
                proxySetter.setProxy(hProxy);
                stateSetter.setState(HState.CONNECTED);
            }
        }


        if (hProxy != null && ((lastType == 2 && b[0] == 0) || lastType == 0)) {
            hProxy.getInHandler().act(packet);
            lastType = isLast ? 2 : 0;
        }
        else if (hProxy != null && ((lastType == 2 && b[0] == 1) || lastType == 1)) {
            hProxy.getOutHandler().act(packet);
            lastType = isLast ? 2 : 1;
        }
        else {
            proxyProvider.abort();
        }
    }

    @OnClose
    public void onClose(Session session) throws IOException {
        proxyProvider.abort();
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        proxyProvider.abort();
    }
}
