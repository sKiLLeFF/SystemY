/**
 * Created by JJTP on 25/10/2016.
 * This class is the Main class on a node (client computer). It initializes a node.
 */
package JJTP_DS_UA;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.*;
import java.lang.String;
import java.util.Collections;
import java.util.Scanner;


// Onder: Node
public class Main_node
{
    static Node node;
    static GUI gui;

    public static void main(String[] args) throws SocketException, UnknownHostException
    {
        node = new Node();
        gui = new GUI(); // Zet dit in commentaar als men de GUI tijdelijk niet nodig heeft
        setNodeName();
        node.getIP();
        node.listenMC();
        //node.updateFiles();
        //node.receiveFiles();

        gui.logOutButtonActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                node.shutDown();

            }
        });

        /*
        try
        {
            Thread.sleep(5000);
        } catch (InterruptedException e)
        {
            e.printStackTrace();
        }

        node.testFailure("192.168.1.2"); // om te testen?
        */
    }

    private static void setNodeName()
    {
        String name;
        name = gui.setNodeName();
        node.checkName(name);
        while(node.wrongName)
        {
                name = gui.setNodeNameAgain();
                node.checkName(name);
        }

        node.startUp(name); // bevat sendMC(), getStartupInfoFromNS() en testBootstrapDiscovery()
        gui.openPanel();
    }
}
