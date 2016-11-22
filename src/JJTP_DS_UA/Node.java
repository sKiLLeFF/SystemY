/**
 * Created by JJTP on 25-10-2016.
 * A Node has a node name (that will be hashed) and IP address.
 * This class contains methods to calculate a node's position in the network and to update it's neighbours
 * when the network changes.
 */
package JJTP_DS_UA;

import javax.xml.crypto.Data;
import java.io.*;
import java.net.*;
import java.rmi.AlreadyBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

// Boven: Main_Node
// Onder: Node_NameServerRMI, Node_nodeRMI_Receive, Node_nodeRMI_Transmit
public class Node {
    String name, newNodeIP;
    Inet4Address ip;
    Node_NameServerRMI NScommunication;
    Node_nodeRMI_Receive nodeRMIReceive;
    //Node_nodeRMI_Transmit nodeRMITransmit;
    int ownHash, prevHash, nextHash, newNodeHash; //newHash = van nieuwe node opgemerkt uit de multicast
    boolean onlyNode, lowEdge, highEdge, shutdown = false, wrongName;
    HashMap<String, FileMarker> fileMarkerMap;
    File fileDir;
    File[] fileList;

    // Node constructor
    public Node() throws SocketException, UnknownHostException {
        getIP();
        NScommunication = new Node_NameServerRMI();
        bindNodeRMIReceive(); // RMI Node-Node
    }

    // Op registerpoort 9876 wordt de Node_nodeRMI_Receive klasse verbonden op een locatie
    public void bindNodeRMIReceive() {
        try {
            nodeRMIReceive = new Node_nodeRMI_Receive(this); //RMIclass maken + referentie naar zichzelf doorgeven (voor buren te plaatsen)
            String bindLocation = "NodeSet";
            Registry reg = LocateRegistry.createRegistry(1099); // Standaardpoort 1099!
            reg.bind(bindLocation, nodeRMIReceive);
            System.out.println("Node is reachable at" + bindLocation);
            System.out.println("java RMI registry created.");
        } catch (AlreadyBoundException | RemoteException e) {
            e.printStackTrace();
            System.err.println("java RMI registry already exists.");
        }
    }

    // Opstarten van de Node: Naam instellen, zijn eigen MultiCast sturen (anderen laten weten) en startup info ophalen
    public void startUp(String name) {
        this.name = name;
        ownHash = calcHash(name);
        sendMC();
        try {
            Thread.sleep(2000); // Belangrijk: Andere Nodes moeten eerst de MC ontvangen
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        getStartupInfoFromNS();
        testBootstrapDiscovery();
        //loadFiles();
    }

    public void shutDown() {
        shutdown = true; //overbodig

        if (prevHash == ownHash && nextHash == ownHash)
            NScommunication.deleteNode(ownHash); //delete eigen node uit de map van de server
        else {
            NScommunication.deleteNode(ownHash); //delete eigen node uit de map van de server
            if (NScommunication.getMapsize() == 1) //er schiet nog maar 1 node over
            {
                String lastNodeIP = NScommunication.getLastNodeIP(); //zet de "onlynode" boolean op true van die laatst overgebleven node
                Node_nodeRMI_Transmit nRMIt = new Node_nodeRMI_Transmit(lastNodeIP, this);
                nRMIt.updateOnlyNode();
            }
            updateLeftNeighbour(); //geef zijn linkerbuur aan de rechterbuur
            updateRightNeighbour(); //geeft zijn rechterbuur aan de linkerbuur

        }
        System.exit(0); //terminate JVM
    }

    // Initialisatie: Een naam
    public void checkName(String name) {
        String tempName = name;
        //System.out.println("Choose a name for the node and press enter.");
        //Scanner s = new Scanner(System.in);
        //name = s.nextLine();
        if (tempName.contains(" ") || NScommunication.checkIfNameExists(tempName)) {
            wrongName = true;
            //System.out.println("Your name contains a white space or already exists, please choose another name.");
            //name = s.nextLine();
        } else {
            wrongName = false;
        }
    }

    // Nakijken of de Node op de laagste en/of hoogste rand zit en is Node de eerste Node in de cirkel?
    public void getStartupInfoFromNS() {
        lowEdge = NScommunication.checkIfLowEdge(ownHash);
        highEdge = NScommunication.checkIfHighEdge(ownHash);

        if (NScommunication.checkAmountOfNodes() <= 1) //Deze check is NA dat de node aan de map is toegevoegd
        {
            onlyNode = true;
            prevHash = ownHash;
            nextHash = ownHash;
        } else
            onlyNode = false;
    }


    // Sturen van een MultiCast
    public void sendMC() {
        try {
            int portMC = 12345; // Multicast Port waarop men gaat sturen
            Inet4Address IPMC = (Inet4Address) Inet4Address.getByName("230.0.0.0");
            // Multicast IP range: 224.0.0.0 - 239.255.255.255
            byte[] msg = (name + " " + ip.getHostAddress()).getBytes(); // naam en adres zijn gescheiden door een spatie

            DatagramSocket socket = new DatagramSocket();
            DatagramPacket packet = new DatagramPacket(msg, msg.length);
            packet.setAddress(IPMC);
            packet.setPort(portMC);
            socket.send(packet);

            System.out.println("Multicast message send.");
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Luisteren naar / Ontvangen van een MultiCast
    public void listenMC() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    int portMC = 12345;
                    Inet4Address IPMC = (Inet4Address) Inet4Address.getByName("230.0.0.0");
                    MulticastSocket mcSocket;
                    mcSocket = new MulticastSocket(portMC);
                    mcSocket.joinGroup(IPMC);
                    DatagramPacket packet;

                    while (!shutdown) {
                        packet = new DatagramPacket(new byte[1024], 1024);
                        System.out.println("Waiting for a  multicast message...");
                        mcSocket.receive(packet);
                        String msg = new String(packet.getData(), packet.getOffset(), packet.getLength());
                        ;
                        String[] info = msg.split(" "); // het ontvangen bericht splitsen in woorden gescheiden door een spatie
                        newNodeHash = calcHash(info[0]);
                        newNodeIP = info[1];
                        recalcPosition();
                        System.out.println("Naam: " + info[0]);
                        System.out.println("IP: " + info[1]);
                    }
                    //shutDown();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    // Positie (buren) wordt gehercalculeerd door volgend algoritme
    public void recalcPosition() {
        if (onlyNode) // Enigste Node in de cirkel
        {
            onlyNode = false;
            updateNewNodeNeighbours(newNodeIP);
            prevHash = newNodeHash;
            nextHash = newNodeHash;
            if (newNodeHash < ownHash)
                lowEdge = false;
            else
                highEdge = false;
        } else {
            if (newNodeHash > ownHash && newNodeHash < nextHash) {
                updateNewNodeNeighbours(newNodeIP);
                nextHash = newNodeHash;
            } else if (newNodeHash < ownHash && newNodeHash > prevHash) {
                prevHash = newNodeHash;
            } else if (lowEdge) {
                if (newNodeHash < ownHash) {
                    prevHash = newNodeHash;
                    lowEdge = false;
                } else if (newNodeHash > prevHash) {
                    prevHash = newNodeHash;
                }
            } else if (highEdge) {
                if (newNodeHash < nextHash) {
                    updateNewNodeNeighbours(newNodeIP);
                    nextHash = newNodeHash;
                } else if (newNodeHash > ownHash) {
                    updateNewNodeNeighbours(newNodeIP);
                    nextHash = newNodeHash;
                    highEdge = false;
                }
            }
        }
    }

    // Buren van de Nieuwe Node updaten
    public void updateNewNodeNeighbours(String ipAddr) {
        Node_nodeRMI_Transmit nodeRMITransmit = new Node_nodeRMI_Transmit(ipAddr, this);
        nodeRMITransmit.setNeighbours(ownHash, nextHash);
    }

    public void updateLeftNeighbour() {
        String ip = NScommunication.getIP(prevHash);
        ip = ip.substring(1); // ipaddress = "/192.168.1.4" so delete first character
        Node_nodeRMI_Transmit nodeRMITransmit = new Node_nodeRMI_Transmit(ip, this);
        nodeRMITransmit.updateLeftNeighbour(nextHash); //maak connectie met de linkerbuur en geef rechterbuur door
    }

    public void updateRightNeighbour() {
        String ip = NScommunication.getIP(nextHash);
        ip = ip.substring(1);
        Node_nodeRMI_Transmit nodeRMITransmit = new Node_nodeRMI_Transmit(ip, this);
        nodeRMITransmit.updateRightNeighbour(prevHash);
    }

    public void failureOtherNode(String IP) //ip adrr van falende node
    {
        int[] neighbours = NScommunication.getIDs(IP); //in [0] zit de linkse buur, in [1] zit de rechtse buur
        if (neighbours[0] == neighbours[1])//in dit geval is deze node de laatste node
        {
            onlyNode = true;
            prevHash = ownHash;
            nextHash = ownHash;
        } else if (neighbours[0] == ownHash) //deze node is de linkse buur van de gefaalde node
        {
            Node_nodeRMI_Transmit nodeRMITransmitR = new Node_nodeRMI_Transmit(NScommunication.getIP(neighbours[1]), this);
            nodeRMITransmitR.updateLeftNeighbour(neighbours[0]); //verbindt met de RECHTSEbuur van de GEFAALDE node en update ZIJN LINKSE buur met de linkse van de gefaalde
            nextHash = neighbours[1]; //update jezelf

        } else if (neighbours[1] == ownHash) //deze node is de rechtse buur van de gefaalde node
        {
            Node_nodeRMI_Transmit nodeRMITransmitL = new Node_nodeRMI_Transmit(NScommunication.getIP(neighbours[0]), this);
            nodeRMITransmitL.updateRightNeighbour(neighbours[1]); //verbindt met de LINKSE node van de GEFAALDE node, en update ZIJN RECHTSE buur met de RECHTSE van de gefaalde node
            prevHash = neighbours[0];
        } else {
            Node_nodeRMI_Transmit nodeRMITransmitL = new Node_nodeRMI_Transmit(NScommunication.getIP(neighbours[0]), this);
            nodeRMITransmitL.updateRightNeighbour(neighbours[1]); //verbindt met de LINKSE node van de GEFAALDE node, en update ZIJN RECHTSE buur met de RECHTSE van de gefaalde node
            Node_nodeRMI_Transmit nodeRMITransmitR = new Node_nodeRMI_Transmit(NScommunication.getIP(neighbours[1]), this);
            nodeRMITransmitR.updateLeftNeighbour(neighbours[0]); //verbindt met de RECHTSEbuur van de GEFAALDE node en update ZIJN LINKSE buur met de linkse van de gefaalde
        }
        NScommunication.deleteNode(NScommunication.getID(IP));
    }

    // Berekenen van een hash van een naam (of filenaam)
    public int calcHash(String name) {
        return Math.abs(name.hashCode() % 32768);
    }

    public void getIP() throws UnknownHostException, SocketException {
        boolean hasIP = false;
        Inet4Address IP = null;
        for (NetworkInterface netint : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress inetAddress : Collections.list(netint.getInetAddresses())) {
                System.out.println("Found IP's: " + inetAddress);
                if (inetAddress.toString().contains("192.168.1.")) {
                    hasIP = true;
                    System.out.println("IP Adres: " + inetAddress);
                    IP = (Inet4Address) inetAddress;
                }
            }
        }
        if (hasIP) {
            ip = IP;

        } else {
            System.out.println("IP not found! Type your local IP manually:");
            //Scanner s = new Scanner(System.in);
            //String ipString = s.nextLine();
            //ip = (Inet4Address) ipString;
        }
    }

    public void loadFiles() // TODO: 22/11/2016 testen!
    {
        fileDir = new File("\\Files"); // gaat naar de "Files" directory in de locale projectmap
        fileList = fileDir.listFiles(); //maakt een array van alle files in de directory  !! enkel files geen directories zelf
        for (int i = 0; i < fileList.length; i++) {
            /*
            String fileName = fileList[i].getName();
            int fileNameHash = calcHash(fileList[i].getName());
            FileMarker fileMarker = new FileMarker(fileName,fileNameHash,ownHash);
            fileMarkerMap.put(fileName,fileMarker); //maak bestandfiche aan en zet in de hashmap
            int fileOwnerHash = NScommunication.getNodeFromFilename(fileNameHash);
            if(fileOwnerHash == ownHash)
            {
                fileMarker.setOwnerID(prevHash);
                //prevHash;
            }
            else
            {
                fileMarker.setOwnerID(fileOwnerHash);
                //doorsturen;
            }
            */
            addFile(i, fileList);
        }
    }

    public void updateFiles() // TODO: 22/11/2016 testen!
    {
        new Thread(new Runnable() {
            public void run() {
                int j = 0, amount = 0;
                try {
                    Thread.sleep(30000); // Update na elke 30 seconden
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                File[] newfileList = fileDir.listFiles();

                for (int i = 0; i < newfileList.length; i++) {
                    if (newfileList[i] != fileList[j]) {
                        addFile(i, newfileList);
                        amount++;
                        j--;
                    }
                    j++;
                }
                System.out.println("amount: " + amount);
            }
        }).start();
    }

    public void addFile(int fileIndex, File[] fileList) // TODO: 22/11/2016 testen!
    {
        int index = fileIndex;
        this.fileList = fileList;
        String fileName = fileList[index].getName();
        int fileNameHash = calcHash(fileList[index].getName());
        FileMarker fileMarker = new FileMarker(fileName, fileNameHash, ownHash);
        fileMarkerMap.put(fileName, fileMarker); //maak bestandfiche aan en zet in de hashmap
        int fileOwnerHash = NScommunication.getNodeFromFilename(fileNameHash);
        if (fileOwnerHash == ownHash) {
            fileMarker.setOwnerID(prevHash);
            sendFile(fileList[index], NScommunication.getIP(prevHash));
        } else {
            fileMarker.setOwnerID(fileOwnerHash);
            sendFile(fileList[index], NScommunication.getIP(fileOwnerHash));
        }
    }

    public void replicateFile() {
        //@TODO hier begin ik woensdag
    }

    // TEST: gegevens weergeven van de Node
    public void testBootstrapDiscovery() {
        new Thread(new Runnable() {

            public void run() {
                while (true) {
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    System.out.println("PrevHash: " + prevHash);
                    System.out.println("NextHash: " + nextHash);
                    System.out.println("ownHash: " + ownHash);
                    System.out.println("FirstNode: " + onlyNode);
                    System.out.println("lowEdge: " + lowEdge);
                    System.out.println("highEdge: " + highEdge);
                }

            }

        }).start();

    }

    public void testFailure(String ip) {
        Node_nodeRMI_Transmit node_rmiObj = new Node_nodeRMI_Transmit(ip, this);
        node_rmiObj.setNeighbours(1234, 1234);
    }

    public void sendFile(File file, String IPdest) {
        try {
            System.out.println("Sending file...");
            Socket socket = new Socket(IPdest, 8796);

            OutputStream os = socket.getOutputStream();
            BufferedOutputStream bos = new BufferedOutputStream(os);
            DataOutputStream dos = new DataOutputStream(bos);

            //dos.writeInt((int) file.length());
            long totalBytesRead = 0;
            long length = file.length();

            FileInputStream fis = new FileInputStream(file);
            BufferedInputStream bis = new BufferedInputStream(fis);

            int theByte = 0;
            while ((theByte = bis.read()) != -1) {
                totalBytesRead += theByte;
                bos.write(theByte);
            }
            bis.close();
            dos.close();
            socket.close();
            System.out.println("File sent.");
        } catch (IOException e) {
            System.err.println(e);
        }
    }

    public void receiveFiles()       // public File receiveFile()
    {
        new Thread(new Runnable() {
            File file;

            public void run() {
                while (true) {

                    try {
                        ServerSocket serverSocket = new ServerSocket(1234);
                        Socket clientSocket = serverSocket.accept();

                        InputStream in = clientSocket.getInputStream();
                        BufferedInputStream bis = new BufferedInputStream(in);
                        DataInputStream dis = new DataInputStream(bis);

                        String path = "C:\\Users\\Thomas\\Desktop";

                        long fileLength = dis.readLong();
                        String fileName = dis.readUTF();

                        file = new File(path + "/" + fileName);

                        FileOutputStream fos = new FileOutputStream(file);
                        BufferedOutputStream bos = new BufferedOutputStream(fos);

                        for (int j = 0; j < fileLength; j++) {
                            bos.write(bis.read());
                        }

                        bos.close();

                    } catch (IOException e) {
                        System.err.println(e);
                    }

                }
            }
        }).start();
        //return file; //@FIXME : de file returnen en terug in de thread geraken
    }
}
