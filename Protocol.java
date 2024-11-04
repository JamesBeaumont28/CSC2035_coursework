/*
 * Replace the following string of 0s with your student number
 * 000000000
 */

import java.io.File;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.io.*;
import java.net.*;
import java.util.Arrays;

public class Protocol {

    static final String  NORMAL_MODE="nm"   ; // normal transfer mode: (for Part 1 and 2)
    static final String	 TIMEOUT_MODE ="wt"  ; // timeout transfer mode: (for Part 3)
    static final String	 GBN_MODE ="gbn"  ;    // GBN transfer mode: (for Part 4)
    static final int DEFAULT_TIMEOUT =10  ;     // default timeout in seconds (for Part 3)
    static final int DEFAULT_RETRIES =4  ;    // default number of consecutive retries (for Part 3)

    /*
     * The following attributes control the execution of a transfer protocol and provide access to the
     * resources needed for a file transfer (such as the file to transfer, etc.)
     *
     */

    private InetAddress ipAddress;      // the address of the server to transfer the file to. This should be a well-formed IP address.
    private int portNumber; 		    // the  port the server is listening on
    private DatagramSocket socket;     // The socket that the client bind to
    private String mode;               //mode of transfer normal/with timeout/GBN

    private File inputFile;           // The client-side input file to transfer
    private String inputFileName;      // the name of the client-side input file for transfer to the server
    private String outputFileName ;    //the name of the output file to create on the server as a result of the file transfer
    private long fileSize;            // the size of the client-side input file

    private Segment dataSeg   ;         // the protocol data segment for sending segments with payload read from the input file to the server
    private Segment ackSeg  ;           //the protocol ack segment for receiving ACKs from the server
    private int maxPayload;				//The max payload size of the data segment
    private long remainingBytes;       //the number of bytes remaining to be transferred during execution of a transfer. This is set to the input file size at the start

    private int timeout;          //the timeout in seconds to use for the protocol with timeout (for Part 3)
    private int maxRetries;       //the maximum number of consecutive retries (retransmissions) to allow before exiting the client (for Part 3)(This is per segment)

    private int sentBytes;       //the accumulated total bytes transferred to the server as the result of a file transfer
    private float lossProb;      //the probability of corruption of a data segment during the transfer  (for Part 3)
    private int currRetry;       //the current number of consecutive retries (retransmissions) following a segment corruption (for Part 3)(This is per segment)
    private int totalSegments;   //the accumulated total number of ALL data segments transferred to the server as the result of a file transfer
    private int resentSegments;  //the accumulated total number of data segments resent to the server as a result of timeouts during a file transfer (for Part 3)

    /**************************************************************************************************************************************
     **************************************************************************************************************************************
     * For this assignment, you have to implement the following methods:
     *		sendMetadata()
     *      readData()
     *      sendData()
     *      receiveAck()
     *      sendDataWithError()
     *      sendFileWithTimeout()
     *		sendFileWithGBN()
     * Do not change any method signatures and do not change any other methods or code provided.
     ***************************************************************************************************************************************
     **************************************************************************************************************************************/
    /*
     * This method sends protocol metadata to the server.
     * Sending metadata starts a transfer by sending the following information to the server in the metadata object (defined in MetaData.java):
     *      size - the size of the file to send
     *      name - the name of the file to create on the server
     *      maxSegSize - The size of the payload of the data segment
     * deal with error in sending
     * output relevant information messages for the user to follow progress of the file transfer.
     * This method does not set any of the attributes of the protocol.
     */

    public void sendMetadata() throws IOException {
        System.out.println("----------------------------------------------------");
        System.out.println("SENDER: Sending meta data");
        //creates an instance of the meta data class
        MetaData MetaDataToSend = new MetaData();
        //sets the values of the metadata
        MetaDataToSend.setName(inputFileName);
        MetaDataToSend.setSize(fileSize);
        MetaDataToSend.setMaxSegSize(maxPayload);

        //creates the data pack to send to the user
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectStream = new ObjectOutputStream(outputStream);
        objectStream.writeObject(MetaDataToSend);
        byte[] data = outputStream.toByteArray();
        DatagramPacket sentpacket = new DatagramPacket(data, data.length, ipAddress, portNumber);

        //sends packet
        this.socket.send(sentpacket);
        System.out.println("SENDER: meta data is sent (" + MetaDataToSend.getName() + ", " + MetaDataToSend.getSize() + ", " + MetaDataToSend.getMaxSegSize() + ")");
        System.out.println("----------------------------------------------------");
    }

    /*
     * This method:
     *  	read the next chunk of data from the file into the data segment (dataSeg) payload.
     *  	set the correct type of the data segment
     *  	set the correct sequence number of the data segment.
     *  	set the data segment's size field to the number of bytes read from the file
     * This method DOES NOT:
     * set the checksum of the data segment.
     * The method returns -1 if this is the last data segment (no more data to be read) and 0 otherwise.
     */
    public int readData() {
        //sets segment type to data
        this.dataSeg.setType(SegmentType.Data);

        try {
            //creates the file reader and byte Array for the input file.
            byte[] payloadByteStream = new byte[this.maxPayload];
            FileInputStream myReader = new FileInputStream(this.inputFile);
            myReader.skip((this.fileSize - this.remainingBytes));
            int bytesRead = myReader.read(payloadByteStream,0,this.maxPayload);
            myReader.close();

            //checks if the reader read anything at all and exits if so
            if (bytesRead == -1) {
                System.out.println("SENDER: All Bytes have already been read. | Exiting");
                System.exit(0);
                return -1;
            }

            //sets all required values in dataSeg object
            this.dataSeg.setSq(this.dataSeg.getSq() + 1);
            this.dataSeg.setSize(bytesRead);
            this.dataSeg.setSq(((int)this.fileSize - (int)this.remainingBytes) / this.maxPayload);
            this.dataSeg.setPayLoad(new String(payloadByteStream,0,bytesRead));

            //updates values
            this.remainingBytes -= bytesRead;
            this.sentBytes += bytesRead;
            this.totalSegments++;

            System.out.println(this.dataSeg.getPayLoad());
            //checks if the final data segment has been read and returns -1 otherwise returns 0
            if (bytesRead < maxPayload || this.remainingBytes == 0) {
                return -1;
            } else {
                return 0;
            }

        } catch (IOException e) {
            System.out.println("SENDER: Error during data read. | Exiting.");
            System.out.println(e.getMessage());
            System.exit(0);
            return -1;
        }

    }

    /*
     * This method sends the current data segment (dataSeg) to the server
     * This method:
     * 		computes a checksum of the data and sets the data segment's checksum prior to sending.
     * output relevant information messages for the user to follow progress of the file transfer.
     */
    public void sendData() throws IOException {
        //calculates the packets checksum
        this.dataSeg.setChecksum(checksum(this.dataSeg.getPayLoad(),false));

        //creates the data pack to send to the user
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectStream = new ObjectOutputStream(outputStream);
        objectStream.writeObject(this.dataSeg);
        byte[] data = outputStream.toByteArray();
        DatagramPacket dataPacket = new DatagramPacket(data, data.length, ipAddress, portNumber);

        //sends data packet
        this.socket.send(dataPacket);
        this.totalSegments++;
        this.remainingBytes = remainingBytes - dataSeg.getSize();
        this.sentBytes = this.sentBytes + dataSeg.getSize();
        System.out.println("SENDER: Sending segment: sq:" + this.dataSeg.getSq() + ", size: " + this.dataSeg.getSize() + ", checksum: " + dataSeg.getChecksum() + ", content: (" + dataSeg.getPayLoad() + ")");
    }


    //Decide on the right place to :
    // *  	update the remaining bytes so that it records the remaining bytes to be read from the file after this segment is transferred. When all file bytes have been read, the remaining bytes will be zero
    // *    update the number of total sent segments
    // *    update the number of sent bytes


    /*
     * This method receives the current Ack segment (ackSeg) from the server
     * This method:
     * 		needs to check whether the ack is as expected
     * 		exit of the client on detection of an error in the received Ack
     * return true if no error
     * output relevant information messages for the user to follow progress of the file transfer.
     */
    public boolean receiveAck(int expectedDataSq) {
        byte[] incomingData = new byte[1024];
        DatagramPacket incomingPacket = new DatagramPacket(incomingData, incomingData.length);
        try{
            socket.receive(incomingPacket);
        } catch (IOException e) {
            System.out.println("SENDER: Acknowledgment not received");
            System.out.println("----------------------------------------------------");
            return false;
        }
        byte[] data = incomingPacket.getData();
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(data);
            ObjectInputStream is = new ObjectInputStream(in);
            ackSeg = (Segment) is.readObject();
        } catch (ClassNotFoundException | IOException e) {
            e.printStackTrace();
        }
        //checking if the sequence numbers match
        if (this.ackSeg.getSq() != expectedDataSq) {
            System.out.println("SENDER: Acknowledgment does not match expected Sq: " + expectedDataSq + "| Exiting.");
            System.out.println("----------------------------------------------------");
            System.exit(0);
            return false;
        } else {
            System.out.println("SENDER: ACK sq=" + ackSeg.getSq() + " RECEIVED.");
            System.out.println("----------------------------------------------------");
            return true;
        }
    }

    /*
     * This method sends the current data segment (dataSeg) to the server with errors
     * This method:
     * 	 	may  corrupt the checksum according to the loss probability specified if the transfer mode is with timeout (wt)
     * 		If the count of consecutive retries/retransmissions exceeds the maximum number of allowed retries, the method exits the client with an
     * appropriate error message.
     *	This method does not receive any segment from the server
     * output relevant information messages for the user to follow progress of the file transfer.
     */
    public void sendDataWithError() throws IOException {
        if (this.resentSegments >= this.maxRetries) {
            System.out.println("SENDER: Maximum number of retires has been reached | Exiting....");
            System.out.println("----------------------------------------------------");
            System.exit(0);

        } else {
            //corrupts checksum
            this.dataSeg.setChecksum(checksum(this.dataSeg.getPayLoad(), isCorrupted(this.lossProb)));

            //creates the data pack to send to the user
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectStream = new ObjectOutputStream(outputStream);
            objectStream.writeObject(this.dataSeg);
            byte[] data = outputStream.toByteArray();
            DatagramPacket sentPacket = new DatagramPacket(data, data.length, ipAddress, portNumber);

            //sends segment
            this.socket.send(sentPacket);
            System.out.println("SENDER: Sending segment: sq:" + this.dataSeg.getSq() + ", size: " + this.dataSeg.getSize() + ", checksum: " + dataSeg.getChecksum() + ", content: (" + dataSeg.getPayLoad() + ")");
            System.out.println("----------------------------------------------------");
            //updates relevant values
            this.totalSegments++;
            this.remainingBytes = remainingBytes - dataSeg.getSize();
            this.sentBytes = this.sentBytes + dataSeg.getSize();
            this.currRetry++;
        }
    }

    /*
     * This method transfers the given file using the resources provided by the protocol structure.
     *
     * This method is similar to the sendFileNormal method except that it resends data segments if no ACK for a segment is received from the server.
     * This method:
     *  simulates network corruption of some data segments by injecting corruption into segment checksums (using sendDataWithError() method).
     *  will timeout waiting for an ACK for a corrupted segment and will resend the same data segment.
     *  updates attributes that record the progress of a file transfer. This includes the number of consecutive retries for each segment.
     *
     * output relevant information messages for the user to follow progress of the file transfer.
     * after completing the file transfer, display total segments transferred and the total number of resent segments
     *
     * relevant methods that need to be used include: readData(), sendDataWithError(), receiveAck().
     */
    void sendFileWithTimeout() throws IOException {
        boolean received;

        while (remainingBytes > 0)
        {
            readData();
            received = false;
            while (!received){
                sendDataWithError();
                this.socket.setSoTimeout(1000*this.timeout);
                received = receiveAck(this.dataSeg.getSq());
                if(!received) {
                    System.out.println("SENDER: TIMEOUT ALERT: Re-sending the same segment again, current retry:" + this.currRetry);
                    this.resentSegments++;
                } else {
                    this.currRetry = 0;
                }
            }
        }
        System.out.println("Total Segments "+ this.totalSegments);
        System.out.println("Resent Segments: " + this.resentSegments);
    }

    /*
     *  transfer the given file using the resources provided by the protocol structure using GoBackN.
     */
    void sendFileNormalGBN(int window) throws IOException
    {
        int[] sqArray = new int[window];

        //send the initial window of segments
        System.out.println("---------------Sending the segments in the initial window --------------------------");
        for (int i = 0; i < window; i++) {
            readData();
            sqArray[i] = dataSeg.getSq();
            sendData();
        }
        System.out.println("SENDER: Waiting for an ack and slide the window if the ack number is correct");
        System.out.println("-----------------------------------------------------------");
        //receives most recent acknowledgment before sending next segment
        while (remainingBytes > 0) {
            System.out.println("SENDER: current outstanding Acks " + Arrays.toString(sqArray));
            receiveAck(sqArray[0]);
            System.out.println("SENDER: slide the window and send the next segment");
            for (int i = (0); i < window-1; i++) {
                sqArray[i] = sqArray[i+1];
            }
            System.out.println(Arrays.toString(sqArray));
            readData();
            sqArray[window-1] = dataSeg.getSq();
            sendData();
            System.out.println("-----------------------------------------------------------");
        }
        //receives the remaining acknowledgements
        System.out.println("SENDER: current outstanding Acks");
        System.out.println(Arrays.toString(sqArray));
        for (int i = 0; i < window; i++) {
            receiveAck(sqArray[i]);
        }
        System.out.println("Total Segments "+ this.totalSegments);
    }


    /*************************************************************************************************************************************
     **************************************************************************************************************************************
     **************************************************************************************************************************************
     These methods are implemented for you .. Do NOT Change them
     **************************************************************************************************************************************
     **************************************************************************************************************************************
     **************************************************************************************************************************************/
    /*
     * This method initialises ALL the 19 attributes needed to allow the Protocol methods to work properly
     */
    public void initProtocol(String hostName , String portNumber, String fileName, String outputFileName, String payloadSize, String mode) throws UnknownHostException, SocketException {
        this.portNumber = Integer.parseInt(portNumber);
        this.ipAddress = InetAddress.getByName(hostName);
        this.socket = new DatagramSocket();
        this.inputFile = checkFile(fileName);
        this.inputFileName = fileName;
        this.outputFileName =  outputFileName;
        this.fileSize       =this.inputFile.length();

        this.remainingBytes = this.fileSize;
        this.maxPayload = Integer.parseInt(payloadSize);
        this.mode = mode;
        this.dataSeg = new Segment();
        this.ackSeg = new Segment();

        this.timeout = DEFAULT_TIMEOUT;
        this.maxRetries = DEFAULT_RETRIES;

        this.sentBytes = 0;
        this.lossProb =0;
        this.totalSegments =0;
        this.resentSegments = 0;
        this.currRetry = 0;
    }

    /* transfer the given file using the resources provided by the protocol
     *      attributes, according to the normal file transfer without timeout
     *      or retransmission (for part 2).
     */
    public void sendFileNormal() throws IOException {
        while (this.remainingBytes!=0) {
            readData();
            sendData();
            if(!receiveAck(this.dataSeg.getSq()))  System.exit(0);
        }
        System.out.println("Total Segments "+ this.totalSegments );
    }

    /* calculate the segment checksum by adding the payload
     * Parameters:
     * payload - the payload string
     * corrupted - a boolean to indicate whether the checksum should be corrupted
     *      to simulate a network error
     *
     * Return:
     * An integer value calculated from the payload of a segment
     */
    public static int checksum(String payload, Boolean corrupted)
    {
        if (!corrupted)
        {
            int i;

            int sum = 0;
            for (i = 0; i < payload.length(); i++)
                sum += (int)payload.charAt(i);
            return sum;
        }
        return 0;
    }

    /* used by Client.java to set the loss probability (for part 3)*/
    public void setLossProb(float loss) {
        this.lossProb = loss;
    }

    /*
     * returns true with the given probability
     *
     * The result can be passed to the checksum function to "corrupt" a
     * checksum with the given probability to simulate network errors in
     * file transfer.
     *
     */
    private static Boolean isCorrupted(float prob) {

        double randomValue = Math.random();  //0.0 to 99.9
        return randomValue <= prob;
    }

    /* check if the input file does exist before sending it */
    private static File checkFile(String fileName)
    {
        File file = new File(fileName);
        if(!file.exists()) {
            System.out.println("SENDER: File does not exists");
            System.out.println("SENDER: Exit ..");
            System.exit(0);
        }
        return file;
    }
}
