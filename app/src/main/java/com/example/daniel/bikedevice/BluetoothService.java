
package com.example.daniel.bikedevice;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;

// This class does all the work for setting up and managing Bluetooth connections with other devices. It has a thread that listens for incoming connections, a thread for connecting with a device, and a thread for performing data transmissions when connected.
public class BluetoothService {

    // Debugging
    private static final String TAG = "BluetoothReadService";
    private static final boolean D = true;

    //Hardcodes the UUID for the Bluetooth connection - The initial "00001101" decribes how the connection will be used for serial communication, through the serial port. The last part "0000-1000-8000-00805F9B34FB" is the base UUID, which converts the inital UUID code into a 128-bit UUID.
    private static final UUID SerialPortServiceClass_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Member fields
    private final BluetoothAdapter mAdapter;
    private final Handler mHandler;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;
    private boolean mAllowInsecureConnections;
    private Context mContext;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_CONNECTING = 1; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 2;  // now connected to a remote device

    //Constructor. Prepares a new Bluetooth session, takes and stores the context and the handler with LaunchActivity.
    public BluetoothService(Context context, Handler handler) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mHandler = handler;
        mContext = context;
        mAllowInsecureConnections = true;
    }

    //Set the current state of the Bluetooth connection and sends it to the handler in LaunchActivity.
    private synchronized void setState(int state) {
        mState = state;
        mHandler.obtainMessage(LaunchActivity.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

    //Return the current connection state.
    public synchronized int getState() {
        return mState;
    }

    //Start the chat service. Specifically start AcceptThread to begin a session in listening (server) mode. Called by the Activity onResume().
    public synchronized void start() {
        if (mConnectThread != null) { // Cancel any thread attempting to make a connection
            mConnectThread.cancel();
            mConnectThread = null;
        }
        if (mConnectedThread != null) { // Cancel any thread currently running a connection
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        setState(STATE_NONE);
    }

    //Start the ConnectThread to initiate a connection to a remote device.
    public synchronized void connect(BluetoothDevice device) {
        if (mState == STATE_CONNECTING) {  // Cancel any thread attempting to make a connection
            if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
        }
        if (mConnectedThread != null) {  // Cancel any thread currently running a connection
            mConnectedThread.cancel(); mConnectedThread = null;
        }
        mConnectThread = new ConnectThread(device); // Start the thread to connect with the given device
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }

    // Start the ConnectedThread to begin managing a Bluetooth connection.
    public synchronized void connected(BluetoothSocket socket) {
        if (mConnectThread != null) {           // Cancel the thread that completed the connection
            mConnectThread.cancel();
            mConnectThread = null;
        }
        if (mConnectedThread != null) {         // Cancel any thread currently running a connection
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        mConnectedThread = new ConnectedThread(socket);     // Start the thread to manage the connection and perform transmissions
        mConnectedThread.start();
        setState(STATE_CONNECTED);
    }

    //Stop all threads
    public synchronized void stop() {
        if (D)
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        setState(STATE_NONE);
    }

    //Write to the ConnectedThread in an unsynchronized manner
    public void write(byte[] out) {
        ConnectedThread r;  // Create temporary object
        synchronized (this) {   // Synchronize a copy of the ConnectedThread
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        r.write(out);   // Perform the write unsynchronized
    }

    //Indicate that the connection attempt failed and notify the UI Activity.
    private void connectionFailed() {
        setState(STATE_NONE);
    }

    // Indicate that the connection was lost and notify the UI Activity.
    private void connectionLost() {
        setState(STATE_NONE);
    }

    // This thread runs while attempting to make an outgoing connection with a device. It runs straight through the connection either succeeds or fails.
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;
            try {       // Get a BluetoothSocket for a connection with the given BluetoothDevice
                if ( mAllowInsecureConnections ) {
                    Method method;
                    method = device.getClass().getMethod("createRfcommSocket", new Class[] { int.class } );
                    tmp = (BluetoothSocket) method.invoke(device, 1);
                } else {
                    tmp = device.createRfcommSocketToServiceRecord( SerialPortServiceClass_UUID );
                }
            } catch (Exception e) {
            }
            mmSocket = tmp;
        }

        public void run() {
            setName("ConnectThread");
            mAdapter.cancelDiscovery();                 // Always cancel discovery because it will slow down a connection
            try {   // Make a connection to the BluetoothSocket
                mmSocket.connect(); // This is a blocking call and will only return on successful connection or an exception
            } catch (IOException e) {
                connectionFailed();
                try {       // Close the socket
                    mmSocket.close();
                } catch (IOException e2) {
                }
                return;
            }
            synchronized (BluetoothService.this) {    // Reset the ConnectThread because we're done
                mConnectThread = null;
            }
            connected(mmSocket);                // Start the connected thread
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
            }
        }
    }

    // This thread runs during a connection with a remote device. It handles all incoming and outgoing transmissions.
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {           // Get the BluetoothSocket input and output streams
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
            }
            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;
            while (true) {  // Keep listening to the InputStream while connected
                try {
                    bytes = mmInStream.read(buffer);  // Read from the InputStream
                } catch (IOException e) {
                    connectionLost();
                    break;
                }
            }
        }

        //Write to the connected outStream.
        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);
            } catch (IOException e) {
            }
        }

        //Cancel socket connection.
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
            }
        }
    }
}
