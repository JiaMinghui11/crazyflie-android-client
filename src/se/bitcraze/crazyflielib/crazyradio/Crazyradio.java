/**
 *    ||          ____  _ __
 * +------+      / __ )(_) /_______________ _____  ___
 * | 0xBC |     / __  / / __/ ___/ ___/ __ `/_  / / _ \
 * +------+    / /_/ / / /_/ /__/ /  / /_/ / / /_/  __/
 *  ||  ||    /_____/_/\__/\___/_/   \__,_/ /___/\___/
 *
 * Copyright (C) 2013 Bitcraze AB
 *
 * Crazyflie Nano Quadcopter Client
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *
 */

package se.bitcraze.crazyflielib.crazyradio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.bitcraze.crazyflielib.AbstractLink;
import se.bitcraze.crazyflielib.IUsbLink;
import se.bitcraze.crazyflielib.crtp.CrtpPacket;

/**
 * Used for communication with the Crazyradio USB dongle
 *
 */
public class Crazyradio extends AbstractLink {

    final Logger mLogger = LoggerFactory.getLogger(this.getClass().getSimpleName());

    // USB parameters
    public final static int CRADIO_VID = 0x1915; //Vendor ID
    public final static int CRADIO_PID = 0x7777; //Product ID

    // Dongle configuration requests
    // See http://wiki.bitcraze.se/projects:crazyradio:protocol for documentation
    private final static int SET_RADIO_CHANNEL = 0x01;
    private final static int SET_RADIO_ADDRESS = 0x02;
    private final static int SET_DATA_RATE = 0x03;
    private final static int SET_RADIO_POWER = 0x04;
    private final static int SET_RADIO_ARD = 0x05;
    private final static int SET_RADIO_ARC = 0x06;
    private final static int ACK_ENABLE = 0x10;
    private final static int SET_CONT_CARRIER = 0x20;
    private final static int SCAN_CHANNELS = 0x21;
    private final static int LAUNCH_BOOTLOADER = 0xFF;

    // configuration constants
    public final static int DR_250KPS = 0;
    public final static int DR_1MPS = 1;
    public final static int DR_2MPS = 2;

    public final static int P_M18DBM = 0;
    public final static int P_M12DBM = 1;
    public final static int P_M6DBM = 2;
    public final static int P_0DBM = 3;

    private IUsbLink mUsbInterface;
    private int mArc;
    private float mVersion; // Crazyradio firmware version
    private String mSerialNumber; // Crazyradio serial number

    public final static byte[] NULL_PACKET = new byte[] { (byte) 0xff };

    /**
     * Number of packets without acknowledgment before marking the connection as
     * broken and disconnecting.
     */
    public static final int RETRYCOUNT_BEFORE_DISCONNECT = 10;

    /**
     * This number of packets should be processed between reports of the link quality.
     */
    public static final int PACKETS_BETWEEN_LINK_QUALITY_UPDATE = 5;

    private Thread mRadioLinkThread;

    private final BlockingDeque<CrtpPacket> mSendQueue;

    /**
     * Create object and scan for USB dongle if no device is supplied
     *
     * @param usbInterface
     */
    public Crazyradio(IUsbLink usbInterface) {
        this.mUsbInterface = usbInterface;
        try {
            this.mUsbInterface.initDevice();
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        /*
        def __init__(self, device=None, devid=0):
            if device is None:
                try:
                    device = _find_devices()[devid]
                except Exception:
                    raise Exception("Cannot find a Crazyradio Dongle")

            self.dev = device
        */

        this.mVersion = mUsbInterface.getFirmwareVersion();

        if (this.mVersion < 0.3) {
            this.mLogger.error("This driver requires Crazyradio firmware V0.3+");
        }

        if (this.mVersion < 0.3) {
            this.mLogger.warn("You should update to Crazyradio firmware V0.4+");
        }

        this.mSerialNumber = mUsbInterface.getSerialNumber();

        // Reset the dongle to power up settings
        this.mLogger.debug("Resetting dongle to power up settings...");
        setDatarate(DR_2MPS);
        setChannel(2);
        this.mArc = -1;
        if (mVersion >= 0.4) {
            setContinuousCarrier(false);
//            // self.set_address((0xE7,) * 5)
            setAddress(new byte[] {(byte) 0xE7, (byte) 0xE7, (byte) 0xE7, (byte) 0xE7, (byte) 0xE7});
            setPower(P_0DBM);
            setArc(3);
            setArdBytes(32);
        }

        this.mSendQueue = new LinkedBlockingDeque<CrtpPacket>();

        this.mVersion = usbInterface.getFirmwareVersion();
        this.mSerialNumber = usbInterface.getSerialNumber();
    }

    /**
     * Connect to the Crazyflie.
     *
     * @throws IllegalStateException if the Crazyradio is not attached
     */
    @Override
    public void connect(ConnectionData connectionData) throws IllegalStateException {
        setChannel(connectionData.getChannel());
        setDatarate(connectionData.getDataRate());

        mLogger.debug("connect()");
        notifyConnectionRequested();

        if (mUsbInterface != null && mUsbInterface.isUsbConnected()) {
            if (mRadioLinkThread == null) {
                mRadioLinkThread = new Thread(radioControlRunnable);
                mRadioLinkThread.start();
            }
        } else {
            mLogger.debug("mConnection is null");
            notifyConnectionFailed("Crazyradio not attached");
            throw new IllegalStateException("Crazyradio not attached");
        }
    }

    @Override
    public void disconnect() {
        mLogger.debug("CrazyradioLink disconnect()");
        if (mRadioLinkThread != null) {
            mRadioLinkThread.interrupt();
            mRadioLinkThread = null;
        }

        if(mUsbInterface != null) {
            mUsbInterface.releaseInterface();
        }

        notifyDisconnected();
    }

    /* ### Dongle configuration ### */

    /**
     * Set the radio channel to be used.
     *
     * @param channel the new channel. Must be in range 0-125.
     */
    public void setChannel(int channel) {
        if (channel < 0 || channel > 125) {
            throw new IllegalArgumentException("Channel must be an integer value between 0 and 125");
        }
        sendVendorSetup(SET_RADIO_CHANNEL, channel, 0, null);
    }

    /**
     * Set the radio address to be used.
     * The same address must be configured in the receiver for the communication to work.
     *
     * @param address the new address with a length of 5 byte.
     * @throws IllegalArgumentException if the length of the address doesn't equal 5 bytes
     */
    public void setAddress(byte[] address) {
        if (address.length != 5) {
            throw new IllegalArgumentException("Radio address must be 5 bytes long");
        }
        sendVendorSetup(SET_RADIO_ADDRESS, 0, 0, address);
    }

    /**
     * Set the radio data rate to be used.
     *
     * @param rate new data rate. Possible values are in range 0-2.
     */
    public void setDatarate(int datarate) {
        if (datarate < 0 || datarate > 2) {
            throw new IllegalArgumentException("Data rate must be an int value between 0 and 2");
        }
        sendVendorSetup(SET_DATA_RATE, datarate, 0, null);
    }

    /**
     * Set the radio power to be used
     *
     * @param power
     */
    public void setPower(int power) {
        //TODO: add argument check
        sendVendorSetup(SET_RADIO_POWER, power, 0, null);
    }

    /**
     * Set the ACK retry count for radio communication
     *
     * Set how often the radio will retry a transfer if the ACK has not been received.
     *
     * @param arc the number of retries.
     * @throws IllegalArgumentException if the number of retries is not in range 0-15.
     */
    public void setArc(int arc) {
        if (arc < 0 || arc > 15) {
            throw new IllegalArgumentException("Count must be in range 0-15");
        }
        sendVendorSetup(SET_RADIO_ARC, arc, 0, null);
        this.mArc = arc;
    }

    /**
     * Set the ACK retry delay for radio communication
     *
     * Configure the time the radio waits for the acknowledge.
     *
     * @param us microseconds to wait. Will be rounded to the closest possible value supported by the radio.
     */
    public void setArdTime(int us) {
        /*
        # Auto Retransmit Delay:
        # 0000 - Wait 250uS
        # 0001 - Wait 500uS
        # 0010 - Wait 750uS
        # ........
        # 1111 - Wait 4000uS
        */
        // Round down, to value representing a multiple of 250uS
        int t = (int) Math.round(us / 250.0) - 1;
        if (t < 0) {
            t = 0;
        } else if (t > 0xF) {
            t = 0xF;
        }
        sendVendorSetup(SET_RADIO_ARD, t, 0, null);
    }

    /**
     * Set the length of the ACK payload.
     *
     * @param nbytes number of bytes in the payload.
     * @throws IllegalArgumentException if the payload length is not in range 0-32.
     */
    public void setArdBytes(int nbytes) {
        if (nbytes < 0 || nbytes > 32) {
            throw new IllegalArgumentException("Payload length must be in range 0-32");
        }
        sendVendorSetup(SET_RADIO_ARD, 0x80 | nbytes, 0, null);
    }

    /**
     * Set the continuous carrier mode. When enabled the radio chip provides a
     * test mode in which a continuous non-modulated sine wave is emitted. When
     * this mode is activated the radio dongle does not transmit any packets.
     *
     * @param active <code>true</code> to enable the continuous carrier mode
     */
    public void setContinuousCarrier(boolean active) {
        sendVendorSetup(SET_CONT_CARRIER, (active ? 1 : 0), 0, null);
    }

    public boolean hasFwScan() {
        /*
          #return self.version >= 0.5
          return mUsbInterface.getFirmwareVersion() > 0.5;
          # FIXME: Mitigation for Crazyradio firmware bug #9
        */
        return false;
    }

    /**
     * Scan all channels between 0 and 125
     *
     * @return list of channels
     */
    public List<Integer> scanChannels1() {
        return scanChannels(0, 125);
    }

    public List<Integer> scanChannels(int start, int stop) {
        List<Integer> result = new ArrayList<Integer>();

        if (mUsbInterface != null && mUsbInterface.isUsbConnected()) {
            if (hasFwScan()) {
                result.addAll(firmwareScan(start, stop));
            } else {
                // Slow PC-driven scan
                mLogger.debug("Slow scan...");
                // for i in range(start, stop + 1):
                for (int channel = start; channel <= stop; channel++) {
                    if(scanSelected(channel, NULL_PACKET)) {
                        mLogger.debug("Found channel: " + channel);
                        result.add(channel);
                    }
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        } else {
            mLogger.warn("Crazyradio not attached.");
        }
        return result;
    }

    /**
     * Scan for available channels.
     *
     * @return array containing the found channels and datarates.
     * @throws IOException
     * @throws IllegalStateException if the Crazyradio is not attached (the connection is <code>null</code>).
     */

    public ConnectionData[] scanChannels() throws IOException {
        return scanChannels(false);
    }

    /**
     * Scan for available channels.
     *
     * @param useSlowScan
     * @return array containing the found channels and datarates.
     * @throws IOException
     * @throws IllegalStateException if the Crazyradio is not attached (the connection is <code>null</code>).
     */
    public ConnectionData[] scanChannels(boolean useSlowScan) throws IOException {
        List<ConnectionData> result = new ArrayList<ConnectionData>();
        if (mUsbInterface.isUsbConnected()) {
            // null packet
            final byte[] packet = CrtpPacket.NULL_PACKET.toByteArray();
            final byte[] rdata = new byte[64];

            mLogger.debug("Scanning...");
            // scan for all 3 data rates
            for (int datarate = 0; datarate < 3; datarate++) {
                // set data rate
                mUsbInterface.sendControlTransfer(0x40, SET_DATA_RATE, datarate, 0, null);
                if (useSlowScan) {
                    result.addAll(scanChannelsSlow(datarate));
                } else {
                    mLogger.debug("Fast firmware scan...");
                    //long transfer timeout (1000) is important!
                    mUsbInterface.sendControlTransfer(0x40, SCAN_CHANNELS, 0, 125, packet);
                    final int nfound = mUsbInterface.sendControlTransfer(0xc0, SCAN_CHANNELS, 0, 0, rdata);
                    for (int i = 0; i < nfound; i++) {
                        result.add(new ConnectionData(rdata[i], datarate));
                        mLogger.debug("Found channel: " + rdata[i] + " Data rate: " + datarate);
                    }
                }
            }
        } else {
            mLogger.debug("connection is null");
            throw new IllegalStateException("Crazyradio not attached");
        }
        return result.toArray(new ConnectionData[result.size()]);
    }

    /**
     * Slow manual scan
     *
     * @param datarate
     * @throws IOExceptionsbInterface
     */
    private List<ConnectionData> scanChannelsSlow(int datarate) throws IOException {
        mLogger.debug("Slow manual scan...");
        List<ConnectionData> result = new ArrayList<ConnectionData>();

        for (int channel = 0; channel < 126; channel++) {
            // set channel
            mUsbInterface.sendControlTransfer(0x40, SET_RADIO_CHANNEL, channel, 0, null);

            byte[] receiveData = new byte[33];
            final byte[] sendData = CrtpPacket.NULL_PACKET.toByteArray();

            mUsbInterface.sendBulkTransfer(sendData, receiveData);
            if ((receiveData[0] & 1) != 0) { // check if ack received
                result.add(new ConnectionData(channel, datarate));
                mLogger.debug("Channel found: " + channel + " Data rate: " + datarate);
            }
            try {
                Thread.sleep(20, 0);
            } catch (InterruptedException e) {
                mLogger.error("scanChannelsSlow InterruptedException");
            }
        }
        return result;
    }

    public boolean scanSelected(int channel, int datarate, byte[] packet) {
        setDatarate(datarate);
        return scanSelected(channel, packet);
    }

    private boolean scanSelected(int channel, byte[] packet) {
        setChannel(channel);
        RadioAck status = sendPacket(packet);
        return (status != null && status.isAck());
    }


    /* ### Data transfers ### */

    private List<Integer> firmwareScan(int start, int stop) {
        mLogger.debug("Fast scan...");
        List<Integer> result = new ArrayList<Integer>();
        final byte[] rdata = new byte[64];
        mUsbInterface.sendControlTransfer(0x40, SCAN_CHANNELS, start, stop, NULL_PACKET);
        final int nfound = mUsbInterface.sendControlTransfer(0xc0, SCAN_CHANNELS, 0, 0, rdata);
        for (int i = 0; i < nfound; i++) {
            result.add((int) rdata[i]);
            mLogger.debug("Found channel: " + rdata[i]);
        }
        return result;
    }

    /**
     * Send a packet and receive the ack from the radio dongle.
     * The ack contains information about the packet transmission
     * and a data payload if the ack packet contained any
     *
     * @param dataOut
     */
    public RadioAck sendPacket(byte[] dataOut) {
        RadioAck ackIn = null;
        byte[] data = new byte[33]; // 33?

        if (mUsbInterface == null || !mUsbInterface.isUsbConnected()) {
            return null;
        }
        mUsbInterface.sendBulkTransfer(dataOut, data);

        // if data is not None:
        ackIn = new RadioAck();
        if (data[0] != 0) {
            ackIn.setAck((data[0] & 0x01) != 0);
            ackIn.setPowerDet((data[0] & 0x02) != 0);
            ackIn.setRetry(data[0] >> 4);
            ackIn.setData(Arrays.copyOfRange(data, 1, data.length));
        } else {
            ackIn.setRetry(mArc);
        }
        return ackIn;
    }

    public void sendVendorSetup(int request, int value, int index, byte[] data) {
        // usb.TYPE_VENDOR = 64 <=> 0x40
        int usbTypeVendor = 0x40;
        mUsbInterface.sendControlTransfer(usbTypeVendor, request, value, index, data);
    }

    public float getVersion() {
        return this.mVersion;
    }

    public String getSerialNumber() {
        return this.mSerialNumber;
    }




    @Override
    public boolean isConnected() {
        return mRadioLinkThread != null;
    }

    @Override
    public void sendPacket(CrtpPacket p) {
        this.mSendQueue.addLast(p);
    }

    @Override
    public CrtpPacket receivePacket(int wait) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void startSendReceiveThread() {
        // TODO Auto-generated method stub

    }

    @Override
    public void stopSendReceiveThread() {
        // TODO Auto-generated method stub

    }

    /**
     * Handles communication with the dongle to send and receive packets
     */
    private final Runnable radioControlRunnable = new Runnable() {
        @Override
        public void run() {
            int retryBeforeDisconnectRemaining = RETRYCOUNT_BEFORE_DISCONNECT;
            int nextLinkQualityUpdate = PACKETS_BETWEEN_LINK_QUALITY_UPDATE;

            notifyConnected();

            while (mUsbInterface != null && mUsbInterface.isUsbConnected()) {
                try {
                    CrtpPacket p = mSendQueue.pollFirst(5, TimeUnit.MILLISECONDS);
                    if (p == null) { // if no packet was available in the send queue
                        p = CrtpPacket.NULL_PACKET;
                    }

                    byte[] receiveData = new byte[33];
                    final byte[] sendData = p.toByteArray();

                    final int receivedByteCount = mUsbInterface.sendBulkTransfer(sendData, receiveData);

                    //TODO: extract link quality calculation
                    if (receivedByteCount >= 1) {
                        // update link quality status
                        if (nextLinkQualityUpdate <= 0) {
                            final int retransmission = receiveData[0] >> 4;
                            notifyLinkQualityUpdated(Math.max(0, (10 - retransmission) * 10));
                            nextLinkQualityUpdate = PACKETS_BETWEEN_LINK_QUALITY_UPDATE;
                        } else {
                            nextLinkQualityUpdate--;
                        }

                        if ((receiveData[0] & 1) != 0) { // check if ack received
                            retryBeforeDisconnectRemaining = RETRYCOUNT_BEFORE_DISCONNECT;
                            if (receivedByteCount > 1) {
                                CrtpPacket inPacket = new CrtpPacket(Arrays.copyOfRange(receiveData, 1, 1 + (receivedByteCount - 1)));
                                notifyDataListeners(inPacket);
                            }
                        } else {
                            // count lost packets
                            retryBeforeDisconnectRemaining--;
                            if (retryBeforeDisconnectRemaining <= 0) {
                                notifyConnectionLost("Too many packets lost");
                                disconnect();
                                break;
                            }
                        }
                    } else {
                        mLogger.debug("CrazyradioLink comm error - didn't receive answer");
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    };

}
