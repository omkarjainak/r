import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class RingNode extends UnicastRemoteObject implements Node {
    private int nodeId;
    private final int nextNodeId;
    private final AtomicBoolean isParticipating = new AtomicBoolean(false);
    private final AtomicInteger currentLeader = new AtomicInteger(-1);
    private final AtomicBoolean isAlive = new AtomicBoolean(true);
    private Node cachedNextNode = null;

    public RingNode(int nextNodeId) throws RemoteException {
        this.nextNodeId = nextNodeId;
    }

    @Override
    public void setNodeId(int id) throws RemoteException {
        this.nodeId = id;
    }

    @Override
    public int getNodeId() throws RemoteException {
        return nodeId;
    }

    @Override
    public boolean isAlive() throws RemoteException {
        return isAlive.get();
    }

    @Override
    public void startElection() throws RemoteException {
        System.out.println("[" + nodeId + "] Starting election...");
        isParticipating.set(true);

        Message token = new Message(Message.Type.TOKEN, nodeId, nextNodeId, nodeId);
        passToken(token);
    }

    private void passToken(Message token) throws RemoteException {
        for (int attempts = 0; attempts < 3; attempts++) {
            try {
                if (cachedNextNode == null) {
                    String nextNodeUrl = "rmi://localhost/n" + nextNodeId;
                    cachedNextNode = (Node) Naming.lookup(nextNodeUrl);
                }

                System.out.println("[" + nodeId + "] Passing token to node " + nextNodeId);
                cachedNextNode.receiveMessage(token);
                return;

            } catch (Exception e) {
                System.err.println("[" + nodeId + "] Failed to pass token to node " + nextNodeId + ", attempt " + (attempts + 1));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
                cachedNextNode = null;  // retry fresh
            }
        }

        System.err.println("[" + nodeId + "] Node " + nextNodeId + " is unresponsive. Marking as not alive.");
        isAlive.set(false);
    }

    @Override
    public void receiveMessage(Message message) throws RemoteException {
        System.out.println("[" + nodeId + "] Received " + message);

        switch (message.getType()) {
            case TOKEN:
                if (message.getData() == nodeId && isParticipating.get()) {
                    // Elected as leader
                    currentLeader.set(nodeId);
                    isParticipating.set(false);
                    System.out.println("[" + nodeId + "] I am the leader!");

                    Message leaderMsg = new Message(Message.Type.LEADER, nodeId, nextNodeId, nodeId);
                    passToken(leaderMsg);
                } else {
                    if (nodeId > message.getData()) {
                        message = new Message(Message.Type.TOKEN, nodeId, nextNodeId, nodeId);
                    }
                    passToken(message);
                }
                break;

            case LEADER:
                currentLeader.set(message.getData());
                System.out.println("[" + nodeId + "] New leader is node " + message.getData());
                if (message.getData() != nodeId) {
                    passToken(message);
                }
                break;
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                System.out.println("Usage: java RingNode <nodeId> <nextNodeId>");
                return;
            }

            int nodeId = Integer.parseInt(args[0]);
            int nextNodeId = Integer.parseInt(args[1]);

            RingNode node = new RingNode(nextNodeId);
            node.setNodeId(nodeId);

            String nodeUrl = "rmi://localhost/n" + nodeId;
            Naming.rebind(nodeUrl, node);
            System.out.println("[" + nodeId + "] Node registered at " + nodeUrl);

            // Delay and start election
            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    node.startElection();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

            // Keep the node alive
            while (node.isAlive()) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
