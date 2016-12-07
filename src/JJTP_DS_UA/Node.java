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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// Boven: Main_Node
// Onder: Node_NameServerRMI, Node_nodeRMI_Receive, Node_nodeRMI_Transmit
public class  Node
{
    String name, newNodeIP;
    Inet4Address ip;
    Node_NameServerRMI NScommunication;
    Node_nodeRMI_Receive nodeRMIReceive;
    int ownHash, prevHash, nextHash, newNodeHash, fileNameHash; //newNodeHash = van nieuwe node opgemerkt uit de multicast
    boolean onlyNode, wasOnlyNode, lowEdge, highEdge, shutdown = false, prevHighEdge;
    ConcurrentHashMap<String, FileMarker> fileMarkerMap; // markers met key=naam en filemarker object = value
    File fileDir;
    File[] fileArray;

    // Node constructor
    public Node() throws SocketException, UnknownHostException {
        getIP();
        NScommunication = new Node_NameServerRMI();
        bindNodeRMIReceive(); // RMI Node-Node
        fileMarkerMap = new ConcurrentHashMap<>();
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
        loadFiles();
        updateFiles();
        receiveFile();
        //testBootstrapDiscovery();
    }

    public void shutDown() {
        shutdown = true; //overbodig

        if (prevHash == ownHash && nextHash == ownHash) // fixme: kan dit niet veranderd worden naar if(onlyNode)?
            NScommunication.deleteNode(ownHash); //delete eigen node uit de map van de server
        else
        {
            NScommunication.deleteNode(ownHash); //delete eigen node uit de map van de server
            if (NScommunication.getMapsize() == 1) //er schiet nog maar 1 node over
            {
                String lastNodeIP = NScommunication.getLastNodeIP(); //zet de "onlynode" boolean op true van die laatst overgebleven node
                Node_nodeRMI_Transmit nRMIt = new Node_nodeRMI_Transmit(lastNodeIP, this);
                nRMIt.updateOnlyNode();
            }

            //bestandenregeling todo: SHUTDOWN MOET GETEST WORDEN
            for(File file : fileArray)
            {
                String fileName = file.getName();
                int fileNameHash = calcHash(fileName);
                int nodeHash;

                if(fileMarkerMap.containsKey(fileName) && fileNameHash != ownHash) // Node is owner + filehash verwijst niet naar zichzelf -> replicated file
                {
                    FileMarker fileMarker = fileMarkerMap.get(fileName);

                    if(fileMarker.localList.contains(prevHash)) //fixme: geen rekening houden met: als de prev van de prev node het bestand ook lokaal bevat
                    {
                        nodeHash = NScommunication.getNodeFromFilename(prevHash-1); // gebruiken om prev node van jouw prev node te weten te komen
                    }
                    else
                    {
                        nodeHash = prevHash;
                    }

                    fileMarker.setOwnerID(nodeHash);
                    sendFile(file,NScommunication.getIP(nodeHash));
                    Node_nodeRMI_Transmit nodeRMIt = new Node_nodeRMI_Transmit(NScommunication.getIP(nodeHash), this);
                    nodeRMIt.updateFileMarkers(fileMarker);
                    fileMarkerMap.remove(fileMarker.fileName);
                }
                else // Local file
                {
                    int fileOwnerID = NScommunication.getNodeFromFilename(fileNameHash);
                    Node_nodeRMI_Transmit nodeRMIt = new Node_nodeRMI_Transmit(NScommunication.getIP(fileOwnerID), this);

                    if(nodeRMIt.notifyOwner(fileName,ownHash)) // als het bestand nooit gedownload is
                    {
                        file.delete();
                        System.out.println("File: " + fileName + " has been found and deleted from owner and here!");
                    }
                }
            }

            updateLeftNeighbour(); //geef zijn linkerbuur aan de rechterbuur
            updateRightNeighbour(); //geeft zijn rechterbuur aan de linkerbuur

        }
        System.exit(0); //terminate JVM
    }

    // Een andere node heeft een lokaal bestand waarvan ik owner ben, die zal uitgezet worden // todo: hoort bij shutdown, moet getest worden!
    public boolean notifyOwner(String fileName, int ownHash)
    {
        boolean isEmpty = fileMarkerMap.get(fileName).downloadList.isEmpty();
        boolean isDeleted = false;

        if(isEmpty) // zoja bestand verwijderen
        {
            fileMarkerMap.remove(fileName);
            for (File file : fileArray)
            {
                if(file.getName().equals(fileName))
                {
                    isDeleted = file.delete();
                }
            }

            if(!isDeleted)
            {
                System.out.println("!!! File: " + fileName + " hasn't been deleted !!!");
            }
        }
        else
        {
            fileMarkerMap.get(fileName).removeLocalList(ownHash); // verwijdert de shutgedowne node uit de lijst
        }

        return isEmpty;
    }


    // Initialisatie: Een naam
    public boolean checkName(String name)
    {
        boolean isCorrectName;
        //System.out.println("Choose a name for the node and press enter.");
        //Scanner s = new Scanner(System.in);
        //name = s.nextLine();
        if (name.contains(" ") || NScommunication.checkIfNameExists(name))
        {
            isCorrectName = false;
            //System.out.println("Your name contains a white space or already exists, please choose another name.");
            //name = s.nextLine();
        }
        else
        {
            isCorrectName = true;
        }
        return isCorrectName;
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
                        String[] info = msg.split(" "); // het ontvangen bericht splitsen in woorden gescheiden door een spatie
                        newNodeHash = calcHash(info[0]);
                        newNodeIP = info[1];
                        recalcPosition();
                        if(newNodeHash == nextHash) //indien de nieuwe node een rechtse buur wordt: update eigenaar van de files.
                            updateFilesOwner();
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
        prevHighEdge = false;
        wasOnlyNode = false;
        if (onlyNode) // Enigste Node in de cirkel
        {
            onlyNode = false;
            wasOnlyNode = true;
            updateNewNodeNeighbours(newNodeIP);
            prevHash = newNodeHash;
            nextHash = newNodeHash;
            if (newNodeHash < ownHash)
                lowEdge = false;
            else
            {
                highEdge = false;
                prevHighEdge = true;
            }
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
            }
            else if (highEdge)
            {
                if (newNodeHash < nextHash)
                {
                    updateNewNodeNeighbours(newNodeIP);
                    nextHash = newNodeHash;
                }
                else if (newNodeHash > ownHash)
                {
                    updateNewNodeNeighbours(newNodeIP);
                    nextHash = newNodeHash;
                    highEdge = false;
                    prevHighEdge = true;
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
        Node_nodeRMI_Transmit nodeRMITransmit = new Node_nodeRMI_Transmit(ip, this);
        nodeRMITransmit.updateLeftNeighbour(nextHash); //maak connectie met de linkerbuur en geef rechterbuur door
    }

    public void updateRightNeighbour() {
        String ip = NScommunication.getIP(nextHash);
        Node_nodeRMI_Transmit nodeRMITransmit = new Node_nodeRMI_Transmit(ip, this);
        nodeRMITransmit.updateRightNeighbour(prevHash);
    }

    public void failureOtherNode(String IP) //ip adrr van falende node
    {
        int[] neighbours = NScommunication.getIDs(IP); //in [0] zit de linkse buur, in [1] zit de rechtse buur
        if (neighbours[0] == neighbours[1])//in dit geval is deze node de laatste node
        {
            onlyNode = true;
            wasOnlyNode = false;
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

    public void loadFiles() // @TODO testen!
    {
        fileDir = new File("Files"); // gaat naar de "Files" directory in de locale projectmap
        fileArray = fileDir.listFiles(); //maakt een array van alle files in de directory  !! enkel files geen directories zelf
        for (int i = 0; i < fileArray.length; i++) {
            addFile(fileArray[i]);
        }
    }

    public void updateFiles() //@TODO testen!
                                //@TODO lijst van gerepliceerde bijhouden; moeten niet terug gerepliceerd worden.
    {
        new Thread(new Runnable() {
            public void run()
            {
                try
                {
                    Thread.sleep(30000); // Update na elke 30 seconden
                } catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
                File[] newFileArray = fileDir.listFiles();
                List<File> newFileList = new ArrayList<>(Arrays.asList(newFileArray));
                List<File> oldFileList = new ArrayList<>(Arrays.asList(fileArray));
                fileArray = newFileArray;
                newFileList.removeAll(oldFileList);
                if(!newFileList.isEmpty())
                {
                    for(int i=0;i<newFileList.size();i++)
                    {
                        addFile(newFileList.get(i));
                    }
                }
            }
        }).start();
    }

    public void addFile(File file) // TODO: testen!
    {
        String fileName = file.getName();
        int fileNameHash = calcHash(file.getName());
        FileMarker fileMarker = new FileMarker(fileName, fileNameHash, ownHash);
        fileMarkerMap.put(fileName, fileMarker); //maak bestandfiche aan en zet in de hashmap
        int fileOwnerID = NScommunication.getNodeFromFilename(fileNameHash);
/*        if (fileOwnerID >= ownHash && fileOwnerID<nextHash)
        {
            fileMarker.setOwnerID(ownHash);
            sendFile(file, NScommunication.getIP(prevHash));

        }
        else if(fileOwnerID < ownHash && fileOwnerID > 0)
        {
            fileMarker.setOwnerID(prevHash); //update de eigenaar in de filemarker
            sendFile(file, NScommunication.getIP(prevHash)); //stuur file naar de eigenaar
            Node_nodeRMI_Transmit nodeRMIt = new Node_nodeRMI_Transmit(NScommunication.getIP(prevHash), this);
            nodeRMIt.updateFileMarkers(fileMarker); //update de filemarkermap bij de eigenaar
            fileMarkerMap.remove(fileMarker.fileName); //verwijder de filemarker uit de eigen map
        }*/
        if(fileOwnerID == ownHash)
        {
            fileMarker.setOwnerID(ownHash);
            if(!onlyNode)
            {
                sendFile(file, NScommunication.getIP(prevHash));
                fileMarker.addLocalList(prevHash);
            }
        }
        else
        {
            fileMarker.setOwnerID(fileOwnerID);
            sendFile(file, NScommunication.getIP(fileOwnerID));
            Node_nodeRMI_Transmit nodeRMIt = new Node_nodeRMI_Transmit(NScommunication.getIP(fileOwnerID), this);
            nodeRMIt.updateFileMarkers(fileMarker);
            fileMarkerMap.remove(fileMarker.fileName);
        }
    }

    //controleert wanneer een nieuwe node in het netwerk komt of deze node eigenaar wordt van de bestanden (waar deze node eigenaar van is)
    //hier kom je in als de nieuwe node uw nextNode is.
    public synchronized void updateFilesOwner() // @todo testen
    {
        try
        {
            Thread.sleep(5000); //geeft de nieuwe node tijd om op te starten
        } catch (InterruptedException e)
        {
            e.printStackTrace();
        }
        Set<String> keyset = fileMarkerMap.keySet(); // Map van files waar deze node owner van is
        for (String str : keyset)
        {
            fileNameHash = fileMarkerMap.get(str).fileNameHash;
            if (fileNameHash > nextHash)   // elke "normale" situatie (of je bent highedge en er komt een leftedge in
            {
                File file = new File("\\Files\\" + fileMarkerMap.get(str).fileName);
                sendFile(file, newNodeIP);

                Node_nodeRMI_Transmit nodeRMIt = new Node_nodeRMI_Transmit(newNodeIP, this);
                nodeRMIt.updateFileMarkers(fileMarkerMap.get(str));
                fileMarkerMap.remove(str);
            }
            else if(prevHighEdge && fileNameHash < ownHash) //er komt een nieuwe highedge in
            {
                File file = new File("\\Files\\" + fileMarkerMap.get(str).fileName); //opent de file die verstuurd moet worden
                sendFile(file, newNodeIP);
                Node_nodeRMI_Transmit nodeRMIt = new Node_nodeRMI_Transmit(newNodeIP, this);
                nodeRMIt.updateFileMarkers(fileMarkerMap.get(str));
                fileMarkerMap.remove(str);
            }
            else if(wasOnlyNode)
            {
                File file = new File("\\Files\\" + fileMarkerMap.get(str).fileName);
                sendFile(file, newNodeIP);
                //@todo hier wordt een file doorgestuurd, dus filemarker update (met downloads)
            }

        }
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

    public void sendFile(File file, String IPdest)
    {
        int port = 10000;
        String fileLocation = fileDir.toString() + "/" + file.getName();
        System.out.println("sendFile1: "+fileLocation);

        try
        {
            Socket socket = new Socket(IPdest, port);
            System.out.println("SendFile2: Server socket has been set up at port: " + port + ".");

            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            oos.flush();
            oos.writeObject(file.getName());

            //Sending a file
            byte[] b = new byte[1024];
            OutputStream os = socket.getOutputStream();
            BufferedOutputStream bos = new BufferedOutputStream(os, 1024);
            FileInputStream fis = new FileInputStream(fileLocation);
            int i;
            int byteLength = 1024;
            while ((i = fis.read(b, 0, 1024)) != -1)
            {
                //System.out.println("Sending file... " + byteLength + " bytes sent");
                byteLength = byteLength + 1024;
                bos.write(b, 0, i);
                bos.flush();
            }
            socket.shutdownOutput(); // Important: output is being terminated
            System.out.println("SendFile3: Bytes Sent: " + byteLength);

            //Receiving ACK from the client
            //ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            //String ack = (String) ois.readObject();
            //System.out.println("Message from the client: " + ack);

            bos.close();
            fis.close();
            oos.close();
            //ois.close();
            socket.close();

        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public synchronized void updateFileMarker(FileMarker fm)
    {
        fileMarkerMap.put(fm.fileName, fm);
        fm.addLocalList(ownHash); // zichzelf toevoegen aan de lokale lijst van nodes die de file bevatten
    }


    public void receiveFile()
    {

        int port = 10001;

        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    ServerSocket serverSocket = new ServerSocket(port);

                    while(true)
                    {
                        Socket socket = serverSocket.accept();
                        System.out.println("receiveFiles1: Connected to server on port " + port);

                        ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
                        String fileName = (String) ois.readObject();
                        System.out.println("receiveFiles2: Message from the client: " + fileName);

                        //Receiving a file from another node
                        byte[] b = new byte[1024];
                        int length;
                        int byteLength = 1024;
                        FileOutputStream fos = new FileOutputStream(fileDir.getName()+ "/" + fileName); //fixme: als het niet werkt: \\
                            System.out.println("receiveFiles3: "+fileDir.getName() + "/" + fileName);
                        InputStream is = socket.getInputStream();
                        BufferedInputStream bis = new BufferedInputStream(is, 1024);
                        while ((length = bis.read(b, 0, 1024)) != -1)
                        {
                            //System.out.println("Receiving file... " + byteLength + " bytes received");
                            byteLength = byteLength + 1024;
                            fos.write(b, 0, length);
                        }
                        System.out.println("receiveFiles4: Bytes Written: " + byteLength);

                        //Sending an ACK to the server
                        //ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
                        //oos.flush();
                        //oos.writeObject("ACKNOWLEDGE");
                        //System.out.println("ACK sent.");

                        bis.close();
                        fos.close();
                        ois.close();
                        //oos.close();
                        socket.close();
                    }
                } catch (IOException | ClassNotFoundException e)
                {
                    e.printStackTrace();
                }
            }
        }).start();
    }

}
