package com.example.daniel.bikedevice;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.NfcEvent;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.android.volley.AuthFailureError;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Enumeration;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.SecretKeySpec;

public class LaunchActivity extends AppCompatActivity implements LocationListener, CompoundButton.OnCheckedChangeListener, SensorEventListener, NfcAdapter.CreateNdefMessageCallback {

    //Bluetooth Variables
    byte[] buffer = new byte[1];
    private BluetoothAdapter mBluetoothAdapter = null;
    private static BluetoothService mSerialService = null;
    public static final int MESSAGE_STATE_CHANGE = 1;
    private static final int REQUEST_CONNECT_DEVICE = 1; //onActivityResult ID number for connecting devices in the DeviceListActivity class.
    private static final int REQUEST_ENABLE_BT = 2; //onActivityResult ID number for enabling bluetooth -> class not created by WiMB.

    //Menu Variables
    private MenuItem mMenuItemStartStopRecording;
    private MenuItem mMenuItemConnect;

    //GPS Variables
    static int gpsSwitchState;
    protected LocationManager locationManager;
    protected LocationListener locationListener;
    protected Context context;
    private static final int TIME_BETWEEN_GPS_MEASUREMENTS = 5000; //milliseconds
    private long startTimeGps = 0;
    Switch gpsSwicth;

    //Accelerometer Variables
    private SensorManager mSensorManager;
    private Sensor mSensor;
    private long startTime, thresholdStartTime = 0;
    private float last_x, last_y, last_z = 0;
    private static final float SHAKE_SENSOR_THRESHOLD = 5; //m/s^2
    private static final int TIME_SENSOR_THRESHOLD = 2000; //milliseconds
    private static final int COUNTER_SENSOR_THRESHOLD = 5; //no unit
    private static final int TIME_BETWEEN_SENSOR_MEASUREMENTS = 200; //milliseconds
    static int sensorSwitchState,movingCounter;
    Switch sensorSwitch;

    //NFC Variables
    NfcAdapter mNfcAdapter;
    EditText nfcTextOut;
    TextView TextIn;
    static int lockState, nfcCounter;
    Switch lockSwitch;
    static String bikeIdString  = "BD1";

    //RSA Variables
    static Key publicKey, privateKey, appPubKeyObj;

    //AES Variables
    static SecretKeySpec symKey;

    //Peer-to-Peer Variables
    int dstPort= 8080;
    String IPAddress, msgToBike, AppIP, receivedMessage;
    ServerSocket serverSocket;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);


        //Bluetooth Setup
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mSerialService = new BluetoothService(this, mHandlerBT);

        //Simple Bluetooth Button Setup
        Button sendBut = (Button) findViewById(R.id.sendBut);
        sendBut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CharSequence cs = "q";
                sendText(cs);
            }
        });

        //GPS Setup
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {return; }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
        gpsSwicth = (Switch) findViewById(R.id.gpsSwitch);
        gpsSwicth.setOnCheckedChangeListener(this);
        gpsSwitchState = 0;


        //Sensor Setup
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mSensorManager.registerListener(this, mSensor , SensorManager.SENSOR_DELAY_NORMAL);
        sensorSwitch = (Switch) findViewById(R.id.motionSensorsSwitch);
        sensorSwitch.setOnCheckedChangeListener(this);
        sensorSwitchState = 0;
        movingCounter = 0;

        //THIS IT NEEDED FOR NFC CONTACT. (or another way to see the NFC in- and output)
        //NFC Setup
        nfcTextOut = (EditText) findViewById(R.id.nfcTextOut);
        TextIn = (TextView) findViewById(R.id.TextIn);
        mNfcAdapter = NfcAdapter.getDefaultAdapter(this); // Check for available NFC Adapter
        if (mNfcAdapter == null) {
            Toast.makeText(this, "NFC is not available", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        mNfcAdapter.setNdefPushMessageCallback(this, this); // Register callback
        lockSwitch = (Switch) findViewById(R.id.lockSwitch);
        lockSwitch.setOnCheckedChangeListener(this);
        nfcCounter = 0;

        publicKey = null;
        privateKey = null;
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(1024);
            KeyPair kp = kpg.genKeyPair();
            publicKey = kp.getPublic();
            privateKey = kp.getPrivate();
        } catch (Exception e) {
        }

        //Peer-to-peer setup
        IPAddress = getIpAddress();
        AppIP = "192.168.0.101";
        msgToBike = "moving,"+bikeIdString;
        Log.d("IPAddress: ", "my IP: "+IPAddress + " - App IP:"+AppIP+ " - port: "+dstPort);

        Thread socketServerThread = new Thread(new SocketServerThread());
        socketServerThread.start();


    }

    @Override
    public void onPause(){
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        sensorSwitch = (Switch) findViewById(R.id.motionSensorsSwitch);
        sensorSwitch.setOnCheckedChangeListener(this);
        lockSwitch = (Switch) findViewById(R.id.lockSwitch);
        lockSwitch.setOnCheckedChangeListener(this);
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(getIntent().getAction())) {  // Check to see that the Activity started due to an Android Beam
            processIntent(getIntent());
        }
    }

    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) { //Switch buttons state check to turn gps and sensor on and off.
        switch(buttonView.getId()){ //Check the ID of the switch which was changed.
            case R.id.gpsSwitch:
                if(isChecked) {
                    gpsSwitchState = 1; //Turn on GPS when Switch is ON
                } else {
                    gpsSwitchState = 0; //Turn off GPS when Switch if OFF
                }
                break;
            case R.id.motionSensorsSwitch:
                if(isChecked) {
                    sensorSwitchState = 1; //Turn on sensor when Switch is ON
                } else {
                    sensorSwitchState = 0; //Turn off sensor when Switch if OFF
                }
                break;
            case R.id.lockSwitch:
                if(isChecked){
                    lockState = 1;  //NFC contact will prompt the bike device to lock.
                    CharSequence lockedMsgArduino = "z";
                    sendText(lockedMsgArduino);
                    Toast.makeText(this,"Locked",Toast.LENGTH_LONG).show();
                } else {
                    lockState = 0;  //NFC contact will prompt the bike device to unlock.
                    CharSequence unlockedMsgArduino = "x";
                    sendText(unlockedMsgArduino);
                    Toast.makeText(this,"Unlocked",Toast.LENGTH_LONG).show();
                }
                break;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event){ //When any sensor registers any change this method is call. The method is a requirement from the SensorEventListener class which is implemented to the activity.
        Sensor mySensor = event.sensor;
        if(sensorSwitchState == 1) {                //Checking if the sensor swicth is on. (hint: onCheckedChanged())
            if(startTime == 0) {                    //startTime can either be zero if it is the first time or if it has been reset.
                startTime = System.currentTimeMillis(); //set current time to startTime.
            }else if((System.currentTimeMillis() - startTime) > TIME_BETWEEN_SENSOR_MEASUREMENTS){  //if the difference between now and the startTime is greater than TIME_BETWEEN_SENSOR_MEASUREMENTS continue to store a measurement.
                if (mySensor.getType() == Sensor.TYPE_ACCELEROMETER) {             //Stores x, y and z if the sensor change event is related to the accelerometer.
                    float x = event.values[0];
                    float y = event.values[1];
                    float z = event.values[2];
                    if(last_x == 0 && last_y == 0 && last_z == 0) { //Stores the new axises values in the old axises values if the old values have not been used yet.
                        last_x = x;
                        last_y = y;
                        last_z = z;
                    }else{
                        float threshold_check = Math.abs(x-last_x)+Math.abs(y-last_y)+Math.abs(z-last_z); //Calculate absolute difference between all axises.
                        if (threshold_check > SHAKE_SENSOR_THRESHOLD) { //Check if the absolute difference is higher than the SHAKE_SENSOR_THRESHOLD.
                            if(thresholdStartTime == 0){
                                thresholdStartTime = System.currentTimeMillis(); //start timer when registering initial movement.
                            }
                            movingCounter++; //increase counter each time a high movement is registered.
                        }
                        if((System.currentTimeMillis() - thresholdStartTime > TIME_SENSOR_THRESHOLD)){ //Time is up, since the initial movement.
                            thresholdStartTime = 0;                                                    //Reset timer.
                            if(movingCounter > COUNTER_SENSOR_THRESHOLD) {                             //Check if the amount of counters is higher than COUNTER_SENSOR_THRESHOLD.
                                gpsSwicth.setChecked(true);
                                MyClientTask myClientTask = new MyClientTask(AppIP, dstPort, msgToBike);
                                myClientTask.execute();
                                CharSequence SensorChar = "q";                                         //Send code to Arduino for starting Siren.
                                //sendText(SensorChar);
                                sensorSwitch.setChecked(false);

                            }
                            movingCounter = 0;
                        }
                        last_x = x;             //Stores the new axises values in the old axises values.
                        last_y = y;
                        last_z = z;
                    }
                }
                startTime = 0; //reset startTime.
            }
        }
    }


    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { //Method required, since SensorEventListener is implemented into the activity. However, no action is required here.
    }

    private void sendText(CharSequence text) { //Method used for sending text to Arduino. This method will initially take the text and split it into indivudual letter and call the mapAndSend() method for each letter.
        int n = text.length();
        try {
            for(int i = 0; i < n; i++) {
                char c = text.charAt(i);
                mapAndSend(c);
            }
        } catch (IOException e) {
        }
    }

    private void mapAndSend(int c) throws IOException { //Method used for converting the "char" input from the sendText() method into its equivalent "int" value and afterwards into a byte value. The byte is put into a byte array and used as input for the send() method.
        byte[] mBuffer = new byte[1];
        mBuffer[0] = (byte)c;
        send(mBuffer);
    }

    public void send(byte[] out) { //Method used to send byte arrays to Arduino using the object of BluetoothService class.
        if ( out.length > 0 ) {
            mSerialService.write( out );
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {  //Creates the option menu for connecting to bluetooth devices in the application.
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.option_menu, menu);
        mMenuItemConnect = menu.getItem(0);
        mMenuItemStartStopRecording = menu.getItem(0);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {  //Method called when an item on the menu is clicked. Only one option in this case.
        switch (item.getItemId()) {
            case R.id.connect:
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                return true;
        }
        return false;
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) { //Method called when another activity returns to this activity with a result.
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE: // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) { // Get the device MAC address
                    String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS); // Get the BluetoothDevice object
                    BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address); // Attempt to connect to the device
                    mSerialService.connect(device);
                }
                break;
            case REQUEST_ENABLE_BT:
                if (getConnectionState() == BluetoothService.STATE_NONE) { // Launch the DeviceListActivity to see devices and do scan
                    Intent serverIntent = new Intent(this, DeviceListActivity.class);
                    startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
                }
                if (getConnectionState() == BluetoothService.STATE_CONNECTED) {
                    mSerialService.stop();
                    mSerialService.start();
                }
                break;
        }
    }

    public int getConnectionState() { //Method used for getting the current state of the bluetooth connection.
        return mSerialService.getState();
    }

    private final Handler mHandlerBT = new Handler() {
        @Override
        public void handleMessage(Message msg) { // The Handler that gets information back from the BluetoothService
            switch (msg.arg1) {
                case BluetoothService.STATE_CONNECTED:
                    Toast.makeText(getBaseContext(),R.string.title_connected_to,Toast.LENGTH_LONG).show();
                    break;
                case BluetoothService.STATE_CONNECTING:
                    Toast.makeText(getBaseContext(),R.string.title_connecting,Toast.LENGTH_LONG).show();
                    break;
            }
        }
    };

    @Override
    public void onLocationChanged(Location location) { //Method call every time a location change is registered.
        if(gpsSwitchState == 1) {                       //Check if the GPS swicth is on.
            if(startTimeGps == 0) {                    //startTime can either be zero if it is the first time or if it has been reset.
                startTimeGps = System.currentTimeMillis(); //set current time to startTime.
            }else if((System.currentTimeMillis() - startTimeGps) > TIME_BETWEEN_GPS_MEASUREMENTS) {  //if the difference between now and the startTime is greater than TIME_BETWEEN_SENSOR_MEASUREMENTS continue to store a measurement.
                //CharSequence LatAndLon = " - Latitude: " + Double.toString(location.getLatitude()) + " - Longitude: " + Double.toString(location.getLongitude()); //Send the location to Arduino.
                //sendText(LatAndLon);
                RequestQueue queue = Volley.newRequestQueue(getBaseContext());          // Instantiate the RequestQueue.
                String url = "http://192.168.0.104:3000/wimb/updateGps?_id="+bikeIdString+"&latitude="+Double.toString(location.getLatitude())+"&longitude="+Double.toString(location.getLongitude());           // Request a string response from the provided URL.
                StringRequest stringRequest = new StringRequest(Request.Method.POST, url, new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        TextIn.setText("Response OK");
                    }
                    }, new Response.ErrorListener(){
                        @Override
                                public void onErrorResponse(VolleyError error){
                            TextIn.setText("Error");
                        }
                    });
                queue.add(stringRequest);   // Add the request to the RequestQueue.
                startTimeGps = 0;
            }
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { //Method required, since LocationListener is implemented into the activity. However, no action is required here.
    }

    @Override
    public void onProviderEnabled(String provider) { //Method required, since LocationListener is implemented into the activity. However, no action is required here.
    }

    @Override
    public void onProviderDisabled(String provider) { //Method required, since LocationListener is implemented into the activity. However, no action is required here.
    }

    //THIS IT NEEDED FOR NFC CONTACT.
    @Override
    public NdefMessage createNdefMessage(NfcEvent event) {
        nfcCounter++;
        String sendingText = "";
        if(nfcCounter == 1){
            sendingText = "Key: "+ "\n"+ Base64.encodeToString(publicKey.getEncoded(),Base64.NO_WRAP) +"\n"+ "PlainText: " +"\n"+bikeIdString;
            Log.d("wimb",sendingText);
            /*String plainText = ("RSA Works! \n\n" + "Beam Time: " + System.currentTimeMillis());
            byte[] encodedBytes = null;
            try {
                Cipher c = Cipher.getInstance("RSA");
                c.init(Cipher.ENCRYPT_MODE, privateKey);
                encodedBytes = c.doFinal(plainText.getBytes());
                sendingText = "Key: " + "\n"+ Base64.encodeToString(publicKey.getEncoded(),Base64.NO_WRAP) +"\n"+ "Chiper Text: " +"\n"+ Base64.encodeToString(encodedBytes,Base64.NO_WRAP);
            } catch (Exception e) {
            }*/
        }
        if(nfcCounter == 2){
            //Do not create any NDEF message.
        }
        if (nfcCounter == 3){
            symKey = null;
            try {
                SecureRandom sr = SecureRandom.getInstance("SHA1PRNG");
                sr.setSeed("any data used as random seed".getBytes());
                KeyGenerator kg = KeyGenerator.getInstance("AES");
                kg.init(128, sr);
                symKey = new SecretKeySpec((kg.generateKey()).getEncoded(), "AES");
                Log.d("wimb", "KEY: "+ symKey);
            } catch (Exception e) {
                Log.d("wimb", "AES secret key spec error");
            }
            String symKeyString = Base64.encodeToString(symKey.getEncoded(),Base64.NO_WRAP);
            RequestQueue queue = Volley.newRequestQueue(getBaseContext());          // Instantiate the RequestQueue.
            String url = "http://192.168.0.104:3000/wimb/symmetrickey";           // Request a string response from the provided URL.
            JSONObject keyJson = new JSONObject();
            try {
                keyJson.put("key",symKeyString);
                keyJson.put("_id",bikeIdString);
                Log.d("Daniel",keyJson.toString());


            } catch (JSONException e) {
                e.printStackTrace();
            }
            final String attrString = keyJson.toString();
            StringRequest stringRequest = new StringRequest(Request.Method.POST, url, new Response.Listener<String>() {
                @Override
                public void onResponse(String response) {
                    Log.d("wimb", response);
                    if(response.equals("success")) {
                        Log.d("wimb","Symmetric key added to database");
                    }else{
                        Log.d("wimb","Symmetric key NOT added to database");
                        nfcCounter = 0;
                    }
                }
            }, new Response.ErrorListener(){
                @Override
                public void onErrorResponse(VolleyError error){
                }
            }){
                @Override
                public String getBodyContentType() {
                    return String.format("application/json; charset=utf-8");
                }

                @Override
                public byte[] getBody() throws AuthFailureError {
                    try {
                        return attrString == null ? null : attrString.getBytes("utf-8");
                    } catch (UnsupportedEncodingException uee) {
                        VolleyLog.wtf("Unsupported Encoding while trying to get the bytes of %s using %s",attrString, "utf-8");
                        return null;
                    }
                }
            };
            queue.add(stringRequest);   // Add the request to the RequestQueue.
            byte[] encodedBytesBDPriKey = null;
            byte[] encodedBytesAppPubKey = null;
            try {
                //1. Encrypt with bike device private key
                Cipher c1 = Cipher.getInstance("RSA");
                c1.init(Cipher.ENCRYPT_MODE, privateKey);
                encodedBytesBDPriKey = c1.doFinal("true".getBytes());
                Log.d("wimb", "Inner Encryption: "+ encodedBytesBDPriKey);

                //2. Encrypt with application public key
                Cipher c2 = Cipher.getInstance("RSA");
                c2.init(Cipher.ENCRYPT_MODE, appPubKeyObj);
                encodedBytesAppPubKey = c2.doFinal(encodedBytesBDPriKey);
                Log.d("wimb", "Outer Encryption: "+ encodedBytesAppPubKey);

            } catch (Exception e) {
                Log.d("wimb", "AES encryption error");
            }
            sendingText = Base64.encodeToString(encodedBytesAppPubKey,Base64.NO_WRAP);
        }
        NdefMessage msg = new NdefMessage(new NdefRecord[] { createMimeRecord("application/@string/nfc_address", sendingText.getBytes())});
        return msg;
    }

    //THIS IT NEEDED FOR NFC CONTACT.
    // Creates a custom MIME type encapsulated in an NDEF record // @param mimeType
    public NdefRecord createMimeRecord(String mimeType, byte[] payload) {
        byte[] mimeBytes = mimeType.getBytes(Charset.forName("US-ASCII"));
        NdefRecord mimeRecord = new NdefRecord(NdefRecord.TNF_MIME_MEDIA, mimeBytes, new byte[0], payload);
        return mimeRecord;
    }

    @Override
    public void onNewIntent(Intent intent) {
        // onResume gets called after this to handle the intent
        setIntent(intent);
    }

    //THIS IT NEEDED FOR NFC CONTACT.
    //Parses the NDEF Message from the intent.
    void processIntent(Intent intent) {
        Log.d("Daniel","nfcCouter: "+nfcCounter);
        byte[] decodedBytes = null;
        if(nfcCounter == 1) {
            Log.d("Daniel","Case 1");
            Parcelable[] rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
            NdefMessage msg = (NdefMessage) rawMsgs[0];                 // only one message sent during the beam
            String text = new String(msg.getRecords()[0].getPayload()); // record 0 contains the MIME type, record 1 is the AAR, if present
            String[] stringSplit = text.split("\n");
            String pubKey = stringSplit[1];
            String cipherText = stringSplit[3];
            Log.d("WiMB", pubKey + " - " + cipherText);

            byte[] pubByte = Base64.decode(pubKey, Base64.NO_WRAP);
            byte[] cipTextByte = Base64.decode(cipherText, Base64.NO_WRAP);
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                Key pubKeyObe = kf.generatePublic(new X509EncodedKeySpec(pubByte));
                Cipher c = Cipher.getInstance("RSA");
                c.init(Cipher.ENCRYPT_MODE, pubKeyObe);
                decodedBytes = c.doFinal(cipTextByte);
            } catch (Exception e) {
            }
        }
        else if(nfcCounter == 3){
            nfcCounter = 2;
        }
        else if(nfcCounter == 2) {
            String ownerIdString = "";
            Parcelable[] rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
            NdefMessage msg = (NdefMessage) rawMsgs[0];                 // only one message sent during the beam
            String text = new String(msg.getRecords()[0].getPayload()); // record 0 contains the MIME type, record 1 is the AAR, if present
            Log.d("Daniel", " - NDEFMsg: "+ msg);
            Log.d("Daniel",text +  " - getBytes: " + text.getBytes());
            Log.d("Daniel",text);
            String[] stringSplit = text.split("\n");
            Log.d("Daniel","stringSplitLength: "+stringSplit.length);
            String appPubKey = stringSplit[1];
            String cipherText = stringSplit[3];
            Log.d("Daniel","CipherText: "+ cipherText);
            byte[] appPubByte = Base64.decode(appPubKey, Base64.NO_WRAP);
            byte[] cipTextByte = Base64.decode(cipherText, Base64.NO_WRAP);
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                appPubKeyObj = kf.generatePublic(new X509EncodedKeySpec(appPubByte));
                Cipher c = Cipher.getInstance("RSA");
                c.init(Cipher.DECRYPT_MODE, privateKey);
                Log.d("Daniel","Privat Key: "+Base64.encodeToString(privateKey.getEncoded(),Base64.NO_WRAP)+" - Public Key: "+Base64.encodeToString(publicKey.getEncoded(),Base64.NO_WRAP));
                decodedBytes = c.doFinal(cipTextByte);
                ownerIdString = new String(decodedBytes);
                Log.d("Daniel","Result: " + ownerIdString);
            } catch (Exception e) {
            }
            RequestQueue queue = Volley.newRequestQueue(getBaseContext());          // Instantiate the RequestQueue.
            String url = "http://192.168.0.104:3000/wimb/checkbike";           // Request a string response from the provided URL.
            JSONObject attributes = new JSONObject();
            try {
                attributes.put("_id",bikeIdString);
                attributes.put("ownerId", ownerIdString);
                Log.d("Daniel",attributes.toString());

            } catch (JSONException e) {
                e.printStackTrace();
            }
            final String attrString = attributes.toString();
            StringRequest stringRequest = new StringRequest(Request.Method.POST, url, new Response.Listener<String>() {
                @Override
                public void onResponse(String response) {

                    Log.d("wimb", response);
                    if(response.equals("success")) {
                        Toast.makeText(getBaseContext(), "Please make NFC contact again", Toast.LENGTH_LONG).show();
                    }else{
                        Toast.makeText(getBaseContext(), "Device already registered", Toast.LENGTH_LONG).show();
                        nfcCounter = 0;
                    }
                }
            }, new Response.ErrorListener(){
                @Override
                public void onErrorResponse(VolleyError error){
                }
            }){
                @Override
                public String getBodyContentType() {
                    return String.format("application/json; charset=utf-8");
                }

                @Override
                public byte[] getBody() throws AuthFailureError {
                    try {
                        return attrString == null ? null : attrString.getBytes("utf-8");
                    } catch (UnsupportedEncodingException uee) {
                        VolleyLog.wtf("Unsupported Encoding while trying to get the bytes of %s using %s",attrString, "utf-8");
                        return null;
                    }
                }
            };
            queue.add(stringRequest);   // Add the request to the RequestQueue.

        }else{
            String lockStateString = "";
            Parcelable[] rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
            NdefMessage msg = (NdefMessage) rawMsgs[0];                 // only one message sent during the beam
            String text = new String(msg.getRecords()[0].getPayload()); // record 0 contains the MIME type, record 1 is the AAR, if present
            byte[] cipTextByte = Base64.decode(text,Base64.NO_WRAP);
            Log.d("Daniel",text + " - Base64: "+ cipTextByte + " - getBytes: " + text.getBytes());
            Log.d("Daniel", " - NDEFMsg: "+ msg);
            Log.d("Daniel","Byte Array before decryption: "+ cipTextByte);
            try {
                Cipher c = Cipher.getInstance("AES");
                c.init(Cipher.DECRYPT_MODE, symKey);
                Log.d("Daniel","Symmetric Key: "+Base64.encodeToString(symKey.getEncoded(),Base64.NO_WRAP));
                decodedBytes = c.doFinal(cipTextByte);
                Log.d("Daniel","Byte Array after decryption: "+ decodedBytes);
                lockStateString = new String(decodedBytes);
                //lockStateString = Base64.encodeToString(decodedBytes,Base64.NO_WRAP);
                Log.d("Daniel","Content Decrypted: "+ new String(decodedBytes));
            } catch (Exception e) {
            }
            if(lockStateString.equals("true")){
                lockSwitch.setChecked(false);
                sensorSwitch.setChecked(false);
                gpsSwicth.setChecked(false);
                lockStateString = "false";
            }else if(lockStateString.equals("false")){
                lockSwitch.setChecked(true);
                sensorSwitch.setChecked(true);
                lockStateString = "true";
            }
            RequestQueue queue = Volley.newRequestQueue(getBaseContext());          // Instantiate the RequestQueue.
            String url = "http://192.168.0.104:3000/wimb/changelock";           // Request a string response from the provided URL.
            JSONObject attributes = new JSONObject();
            try {
                attributes.put("_id",bikeIdString);
                attributes.put("lock", lockStateString);
                Log.d("Daniel",attributes.toString());

            } catch (JSONException e) {
                e.printStackTrace();
            }
            final String attrString = attributes.toString();
            StringRequest stringRequest = new StringRequest(Request.Method.POST, url, new Response.Listener<String>() {
                @Override
                public void onResponse(String response) {

                    Log.d("wimb", response);
                    if(response.equals("success")) {
                        Toast.makeText(getBaseContext(), "Please make NFC contact again", Toast.LENGTH_LONG).show();
                    }else{
                        Toast.makeText(getBaseContext(), "Device already registered", Toast.LENGTH_LONG).show();
                        nfcCounter = 0;
                    }
                }
            }, new Response.ErrorListener(){
                @Override
                public void onErrorResponse(VolleyError error){
                }
            }){
                @Override
                public String getBodyContentType() {
                    return String.format("application/json; charset=utf-8");
                }

                @Override
                public byte[] getBody() throws AuthFailureError {
                    try {
                        return attrString == null ? null : attrString.getBytes("utf-8");
                    } catch (UnsupportedEncodingException uee) {
                        VolleyLog.wtf("Unsupported Encoding while trying to get the bytes of %s using %s",attrString, "utf-8");
                        return null;
                    }
                }
            };
            queue.add(stringRequest);   // Add the request to the RequestQueue.
        }
        Log.d("Daniel","Finishing ProcessIntent");
        nfcTextOut.setText(new String(decodedBytes));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }


    public class MyClientTask extends AsyncTask<Void, Void, Void> {

        String dstAddress;
        int dstPort;

        String msgToServer;
        String response = "";

        MyClientTask(String addr, int port, String msgTo) {
            dstAddress = addr;
            dstPort = port;
            msgToServer = msgTo;
        }

        @Override
        protected Void doInBackground(Void... arg0) {

            Socket socket = null;
            DataOutputStream dataOutputStream = null;

            try {
                socket = new Socket(dstAddress, dstPort);
                dataOutputStream = new DataOutputStream(socket.getOutputStream());

                if(msgToServer != null){
                    dataOutputStream.writeUTF(msgToServer);
                }

            } catch (UnknownHostException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
                response = "UnknownHostException: " + e.toString();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
                response = "IOException: " + e.toString();
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }

                if (dataOutputStream != null) {
                    try {
                        dataOutputStream.close();
                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            }
            return null;
        }
    }

    private class SocketServerThread extends Thread {

        static final int SocketServerPORT = 8070;

        @Override
        public void run() {
            Socket socket = null;
            DataInputStream dataInputStream = null;
            DataOutputStream dataOutputStream = null;

            try {
                serverSocket = new ServerSocket(SocketServerPORT);

                while (true) {
                    socket = serverSocket.accept();
                    dataInputStream = new DataInputStream(
                            socket.getInputStream());

                    String messageFromClient = "";

                    messageFromClient = dataInputStream.readUTF();

                    Log.d("wimb",messageFromClient);
                    if(messageFromClient.equals("track")){
                        LaunchActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                gpsSwicth.setChecked(true);
                            }
                        });

                    }else if(messageFromClient.equals("alarmswitch")){
                        CharSequence SensorChar = "q";
                        sendText(SensorChar);
                    }


                    LaunchActivity.this.runOnUiThread(new Runnable() {

                        @Override
                        public void run() {
                        }
                    });
                }
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
                final String errMsg = e.toString();
                LaunchActivity.this.runOnUiThread(new Runnable() {

                    @Override
                    public void run() {
                        Log.d("wimb",errMsg);
                    }
                });

            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }

                if (dataInputStream != null) {
                    try {
                        dataInputStream.close();
                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }

                if (dataOutputStream != null) {
                    try {
                        dataOutputStream.close();
                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            }
        }

    }


    private String getIpAddress() {
        String ip = "";
        try {
            Enumeration<NetworkInterface> enumNetworkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (enumNetworkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = enumNetworkInterfaces.nextElement();
                Enumeration<InetAddress> enumInetAddress = networkInterface.getInetAddresses();
                while (enumInetAddress.hasMoreElements()) {
                    InetAddress inetAddress = enumInetAddress.nextElement();

                    if (inetAddress.isSiteLocalAddress()) {
                        ip += "SiteLocalAddress: " + inetAddress.getHostAddress() + "\n";
                    }
                }
            }
        } catch (SocketException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            ip += "Something Wrong! " + e.toString() + "\n";
        }

        return ip;
    }
}




