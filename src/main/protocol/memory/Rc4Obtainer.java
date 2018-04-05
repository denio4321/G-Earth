package main.protocol.memory;

import main.Cacher;
import main.protocol.HConnection;
import main.protocol.HMessage;
import main.protocol.HPacket;
import main.protocol.TrafficListener;
import main.protocol.crypto.RC4;
import main.protocol.packethandler.IncomingHandler;
import main.protocol.packethandler.OutgoingHandler;
import sun.misc.Cache;

import java.util.List;
import java.util.Random;

public class Rc4Obtainer {

    public static final boolean DEBUG = true;

    HabboClient client = null;
    OutgoingHandler outgoingHandler = null;
    IncomingHandler incomingHandler = null;

    String habboVersion = "";
    int pingHeader = -1;

    public Rc4Obtainer() {
        client = HabboClient.create();
    }

    private boolean hashappened1 = false;
    public void setOutgoingHandler(OutgoingHandler handler) {
        outgoingHandler = handler;
        handler.addBufferListener((int addedbytes) -> {
            if (handler.getCurrentIndex() >= 3) this.addedBytes += addedbytes;
            if (!hashappened1 && handler.getCurrentIndex() == 3) {
                hashappened1 = true;
                onSendFirstEncryptedMessage();
            }
        });
    }
    public void setIncomingHandler(IncomingHandler handler) {
        incomingHandler = handler;
    }

    public void setHConnection(HConnection hConnection) {
        hConnection.addTrafficListener(0, message -> {
            if (message.getIndex() == 0 && message.getDestination() == HMessage.Side.TOSERVER) {
                habboVersion = message.getPacket().readString(6);
                if (Cacher.exists(habboVersion +"-pingHeader")) {
                    pingHeader = Integer.parseInt(Cacher.get(habboVersion +"-pingHeader"));
                }
            }
            if (pingHeader == -1 && message.getDestination() == HMessage.Side.TOCLIENT && message.getPacket().length() == 2) {
                pingHeader = message.getPacket().headerId();
                Cacher.add(habboVersion +"-pingHeader", pingHeader+"");
            }
        });
    }

    private volatile int addedBytes = 0;
    private void onSendFirstEncryptedMessage() {
        outgoingHandler.block();
        new Thread(() -> {

            if (DEBUG) System.out.println("[+] send encrypted");
            sleep(20);

            while (pingHeader == -1) {
                sleep(50);
            }
            incomingHandler.block();

            List<MemorySnippet> diff = null;


            // STEP ONE: filtering to obtain one area containing the rc4 data field
            int foundbuffersize = 0;
            while (foundbuffersize == 0) {

                client.pauseProcess();
                diff = client.createMemorySnippetList();
                client.fetchMemory(diff);
                client.resumeProcess();
                this.addedBytes = 0;

                Random rand = new Random();

                if (DEBUG) System.out.println("size: " + getTotalBytesLengthOfDiff(diff));
                int i = 0;
                while (getTotalBytesLengthOfDiff(diff) > 2000) {
                    int am = 0;
                    if (i == 0 || i > 1) {
                        am = rand.nextInt(25) + 5;
                        for (int j = 0; j < am; j++) {
                            incomingHandler.sendToStream(new HPacket(pingHeader).toBytes());
                            outgoingHandler.fakePongAlert();
                            sleep(20);
                        }
                    }
                    sleep(50);
                    int rem = addedBytes;
                    if (i == 0) client.pauseProcess();
                    diff = searchForPossibleRC4Tables(diff);
                    if (i == 0) client.resumeProcess();
                    if (DEBUG) System.out.println("size: " + getTotalBytesLengthOfDiff(diff) + " with changed bytes: " + rem + " should be: " + am * 6);
                    i++;
                }

                foundbuffersize = (int)getTotalBytesLengthOfDiff(diff);
            }





            //diff should only have one element now
            // STEP TWO: obtain the exactly the 256 needed bytes
            //research shows that this equals the first occurence of a number followed by 3 zeros is the start
            //if that number accidentally is zero it won't work so fuck that - cry -
            MemorySnippet snippet = diff.get(0);
            byte[] wannabeRC4data = snippet.getData();
            int result_start_index = -1;
            for (int i = 0; i < snippet.getData().length - 3; i++) {
                if (wannabeRC4data[i] != 0 && wannabeRC4data[i+1] == 0 && wannabeRC4data[i+2] == 0 && wannabeRC4data[i+3] == 0) {
                    result_start_index = i;
                    if (DEBUG) System.out.println(result_start_index);
                    break;
                }
            }

            byte[] data = new byte[256]; // dis is the friggin key
            for (int i = 0; i < 256; i++) data[i] = wannabeRC4data[i*4 + result_start_index];

            if (DEBUG) printByteArray(data);





            // STEP 3: find the i & j values (in our RC4 class called x & y)
            // this goes together with the verification of this actually being the right RC4 table

            MemorySnippet snippet1 = new MemorySnippet(snippet.getOffset(), new byte[snippet.getData().length]);
            client.fetchMemory(snippet1);
            incomingHandler.sendToStream(new HPacket(pingHeader).toBytes());
            outgoingHandler.fakePongAlert();
            sleep(70);

            byte[] lastPongPacket = new byte[6];
            List<Byte> encodedbytelistraw = outgoingHandler.getEncryptedBuffer();
            for (int i = 0; i < 6; i++) {
                lastPongPacket[i] = encodedbytelistraw.get(encodedbytelistraw.size() - 6 + i);
            }

            int counter = 0;
            RC4 result = null;

            while (result == null && counter < 4) {

                byte[] data1 = new byte[256];
                for (int i = 0; i < 256; i++) data1[i] = snippet1.getData()[i*4 + result_start_index];

                //dont panic this runs extremely fast xo
                outerloop:
                for (int x = 0; x < 256; x++) {
                    for (int y = 0; y < 256; y++) {
                        byte[] copy = new byte[256];
                        for (int i = 0; i < 256; i++) {
                            copy[i] = data1[i];
                        }
                        RC4 rc4Tryout = new RC4(copy, x, y);

                        HPacket tryout = new HPacket(rc4Tryout.rc4(lastPongPacket));
                        if (!tryout.isCorrupted()) {
                            result = rc4Tryout;
                            break outerloop;
                        }
                    }
                }
                if (result == null) {
                    result_start_index -= 4;
                }
                counter++;
            }

            //if result = null ud better reload

            if (result == null) {
                System.out.println("please try again.");
                System.out.println("found RC4 table:");
                printByteArray(data);
                return;
            }


            // STEP FOUR: undo all sent packets in the rc4 stream
            List<Byte> enbuf = outgoingHandler.getEncryptedBuffer();
            byte[] encrbuffer = new byte[enbuf.size()];
            for (int i = 0; i < enbuf.size(); i++) {
                encrbuffer[i] = enbuf.get(i);
            }

            result.undoRc4(encrbuffer);


            // STEP FIVE: set the rc4 stream

            if (result != null) {
                outgoingHandler.setRc4(result);
            }
            else {
                System.err.println("Did not find RC4 stream");
            }

            if (DEBUG) System.out.println("[-] send encrypted");
            outgoingHandler.unblock();
            incomingHandler.unblock();
        }).start();
    }

    private List<MemorySnippet> searchForPossibleRC4Tables(List<MemorySnippet> snippets) {
        List<MemorySnippet> result;
        result = client.differentiate2(snippets, addedBytes, addedBytes * 2, 1028);
        addedBytes = 0;

        return result;
    }

    private void printByteArray(byte[] booleans) {
        StringBuilder builder = new StringBuilder();
        for (byte bool : booleans) {
            builder.append(bool);
            builder.append(",");
        }
        System.out.println(builder);
    }
    private long getTotalBytesLengthOfDiff(List<MemorySnippet> snippets) {
        long tot = 0;
        for (MemorySnippet snippet : snippets) {
            tot += (snippet.getData().length);
        }
        return tot;
    }
    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

}
