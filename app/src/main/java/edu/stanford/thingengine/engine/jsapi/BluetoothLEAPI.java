package edu.stanford.thingengine.engine.jsapi;

// newly added
import java.util.ArrayList;
import java.util.List;
import android.bluetooth.BluetoothGatt;
import android.location.Location;
import android.os.Parcel;
import android.util.Log;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothGattCallback;
// import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothGattDescriptor;

import android.app.Activity;
//

import android.bluetooth.BluetoothGattCharacteristic;

import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import edu.stanford.thingengine.engine.service.ControlChannel;
import edu.stanford.thingengine.engine.service.EngineService;
import edu.stanford.thingengine.engine.ui.InteractionCallback;

import java.util.HashMap;
import java.util.UUID;
import android.os.Binder;
import android.os.IBinder;

/**
 * This class includes a small subset of standard GATT attributes for demonstration purposes.
 */

public class BluetoothLEAPI extends JavascriptAPI{

    public Context m_context = null;
    public static final String LOG_TAG = "thingengine.BluetoothLE";

    private BluetoothGatt bluetoothGatt;
    public List<BluetoothGattService> gattServices;
    private static final long BLE_SCAN_PERIOD = 10000;

    private final EngineService ctx;
    private final Handler handler;
    private final BluetoothAdapter adapter;
    private final Map<String, BluetoothDevice> pairing = new HashMap<>();
    private final Map<String, BluetoothDevice> fetchingUuids = new HashMap<>();
    private String bluetoothDeviceAddress;

    private final long FETCH_UUID_TIMEOUT = 20000;

    private Receiver receiver;
    // private volatile boolean discovering;
    private volatile boolean scanning;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;
    private static int connectionState;


    public final static String ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
    public final static String EXTRA_DATA =
            "com.example.bluetooth.le.EXTRA_DATA";


    private static final String TAG = new String("BluetoothAPI");


    //newly added
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

    private static HashMap<String, String> attributes = new HashMap();
    public static String HEART_RATE_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb";
    public static String CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";

    static {
        // Sample Services.
        attributes.put("0000180d-0000-1000-8000-00805f9b34fb", "Heart Rate Service");
        attributes.put("0000180a-0000-1000-8000-00805f9b34fb", "Device Information Service");
        // Sample Characteristics.
        attributes.put(HEART_RATE_MEASUREMENT, "Heart Rate Measurement");
        attributes.put("00002a29-0000-1000-8000-00805f9b34fb", "Manufacturer Name String");
    }

    public static String lookup(String uuid, String defaultName) {
        String name = attributes.get(uuid);
        return name == null ? defaultName : name;
    }

    // end of newly added


    public final static UUID UUID_HEART_RATE_MEASUREMENT =
            UUID.fromString(HEART_RATE_MEASUREMENT);


    private String mheartRateString;


    private class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // bugbugbugbug
            m_context = context;

            switch (intent.getAction()) {
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    onStateChanged(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1),
                            intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, -1));
                    return;

                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                    onDiscoveryFinished();
                    return;

                case BluetoothDevice.ACTION_FOUND:
                    onDeviceFound((BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
                    return;

                case BluetoothDevice.ACTION_CLASS_CHANGED:
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                case BluetoothDevice.ACTION_NAME_CHANGED:
                case BluetoothDevice.ACTION_UUID:
                    onDeviceChanged((BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
                    return;


                case ACTION_GATT_CONNECTED:
                    Log.d(TAG, "action_gatt_connected");
                    return;

                case ACTION_GATT_DISCONNECTED:
                    Log.d(TAG, "action_gatt_disconnected");
                    return;

                case ACTION_GATT_SERVICES_DISCOVERED:
                    Log.d(TAG, "action_gatt_service_discovered");
                    return;

                case ACTION_DATA_AVAILABLE:
                    Log.d(TAG, "action_gatt_data_available");
                    Bundle extras = intent.getExtras();
                    Log.d("HEARTRATE EXTRA", extras.toString());
                    String heartRateString = "";
                    if (extras != null) {
                        heartRateString = extras.getString(EXTRA_DATA);

                    }


                    Log.d("***HEARTRATE******", heartRateString);


                    mheartRateString = heartRateString;
                    // onHeartRateAvailable();

                    onDeviceFound(bluetoothGatt.getDevice());
                    return;
            }
        }
    }

    public BluetoothLEAPI(Handler handler, EngineService ctx, ControlChannel control) {
        super("BluetoothLE", control);
        this.ctx = ctx;
        this.handler = handler;
        bluetoothDeviceAddress = null;


        adapter = ((BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();

        // discovering = false;
        scanning = false;

        registerAsync("start", new GenericCall() {
            @Override
            public Object run(Object... args) throws Exception {
                start();
                return null;
            }
        });

        registerSync("stop", new GenericCall() {
            @Override
            public Object run(Object... args) throws Exception {
                stop();
                return null;
            }
        });

        registerAsync("startDiscovery", new GenericCall() {
            @Override
            public Object run(Object... args) throws Exception {
                startDiscovery(((Number) args[0]).longValue());
                return null;
            }
        });


        registerSync("stopDiscovery", new GenericCall() {
            @Override
            public Object run(Object... args) throws Exception {
                stopDiscovery();
                return null;
            }
        });

        /*
        registerAsync("pairDevice", new GenericCall() {
            @Override
            public Object run(Object... args) throws Exception {
                pairDevice((String) args[0]);
                return null;
            }
        });
*/
        registerAsync("readUUIDs", new GenericCall() {
            @Override
            public Object run(Object... args) throws Exception {
                return readUUIDs((String) args[0]);
            }
        });

        registerAsync("getHeartRate", new GenericCall() {
            @Override
            public Object run(Object... args) throws Exception {
                return readHeartRate();
            }
        });


    }

    private JSONArray getObjFromHeartString()
    {
        try {
            JSONArray ret = new JSONArray();

            JSONObject jsonObj = new JSONObject();
            jsonObj.put("heartrate", mheartRateString);
            ret.put(jsonObj);
            return ret;
        } catch(JSONException e) {
            Log.i(LOG_TAG, "Failed to serialize heartrate", e);
        }
        return null;
    }


    private String readHeartRate() {

        // adapter.startDiscovery();
        scanLeDevice(true);
        long startTime = System.currentTimeMillis();
        synchronized (this) {
            try {
                while (true) {
                    if (mheartRateString != null) {
                        return mheartRateString;
                    }

                    long now = System.currentTimeMillis();
                    long remaining_time = BLE_SCAN_PERIOD  - (now - startTime);
                    if (remaining_time < 1) {
                        return "Try again later.";
                    }

                    long wait_time = Math.min((long) 1000, remaining_time);
                    wait(wait_time);
                }
            } finally {
                if (mheartRateString != null) {
                    return mheartRateString;
                }
                else {
                    return "Try again later.";
                }
            }
        }

    }


    private void start() {
        receiver = new Receiver();

        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_GATT_CONNECTED);
        filter.addAction(ACTION_GATT_DISCONNECTED);
        filter.addAction(ACTION_GATT_SERVICES_DISCOVERED);
        filter.addAction(ACTION_DATA_AVAILABLE);


        // old actions to be removed

        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_CLASS_CHANGED);



        ctx.registerReceiver(receiver, filter, null, handler);
        //testing
        // scanLeDevice(true);
/* testtest
        if (adapter != null) {
            Collection<BluetoothDevice> devices = adapter.getBondedDevices();
            for (BluetoothDevice d : devices)
                onDeviceFound(d);
        }
*/
    }

    private void stop() {
        ctx.unregisterReceiver(receiver);
        receiver = null;
    }

    private void onStateChanged(int newState, int oldState) {
        invokeAsync("onstatechanged", new int[] { newState, oldState });
/*testtesttest
        if (discovering && newState == BluetoothAdapter.STATE_ON && adapter != null)
            adapter.startDiscovery();

*/
    }

    private void onHeartRateAvailable() {
        invokeAsync("onHeartRateAvailable", null);


    }

    private void onDiscoveryFinished() {
        invokeAsync("ondiscoveryfinished", null);
    }

    @NonNull
    private JSONArray uuidsToJson(@Nullable ParcelUuid[] uuids) {
        if (uuids == null)
            return new JSONArray();

        JSONArray ret = new JSONArray();
        for (ParcelUuid uuid : uuids)
            ret.put(uuid.getUuid().toString());
        return ret;
    }

    private JSONObject serializeBtDevice(BluetoothDevice device) throws JSONException {
        JSONObject obj = new JSONObject();
         try {
            obj.put("address", device.getAddress().toLowerCase());
            Log.d("address", device.getAddress().toLowerCase());

            obj.put("uuids", uuidsToJson(device.getUuids()));
            obj.put("heartrate",mheartRateString);

            /*
            obj.put("class", device.getBluetoothClass().getDeviceClass());
            // Log.d("class", device.getBluetoothClass().toString());


            obj.put("alias", device.getName());
            // Log.d("alias", device.getName().toString());

            obj.put("paired", device.getBondState() == BluetoothDevice.BOND_BONDED);
            */

        } catch (JSONException e) {
            Log.i(LOG_TAG, "Failed to serialize BT device", e);
        }
        return obj;
    }

    private void onDeviceFound(BluetoothDevice device) {
        try {
            invokeAsync("ondeviceadded", serializeBtDevice(device));
        } catch (JSONException e) {
            Log.i(LOG_TAG, "Failed to serialize Location", e);
        }
    }

    private void onDeviceChanged(BluetoothDevice device) {
        try {
            invokeAsync("ondevicechanged", serializeBtDevice(device));
        } catch (JSONException e) {
            Log.i(LOG_TAG, "Failed to serialize Location", e);
        }

        synchronized (this) {
            boolean shouldNotify = false;
            String hwAddress = device.getAddress().toLowerCase();
            if (pairing.containsKey(hwAddress)) {
                pairing.put(hwAddress, device);
                shouldNotify = true;
            }
            if (fetchingUuids.containsKey(hwAddress)) {
                fetchingUuids.put(hwAddress, device);
                shouldNotify = true;
            }
            if (shouldNotify)
                notifyAll();
        }
    }







    // le bluetooth specific


    private boolean scanLeDevice(final boolean enable) {
        if (enable) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // TODO
                    scanning = false;
                    Log.d("scanLeDevice", "inside postelayed");
                    // adapter.stopLeScan(leScanCallback);
                    // testtest bugbug 
                    stopScanning();

                }
            }, BLE_SCAN_PERIOD);

           //  TODO
            scanning = true;
            //
            boolean status = adapter.startLeScan(leScanCallback);
            return status;

        } else {
            //  TODO
            scanning = false;
            // adapter.stopLeScan(leScanCallback);
            stopScanning();
        }

        return true;
    }

//newly added bugbug
    public void connectToDevice(BluetoothDevice device) {
        if (bluetoothGatt == null) {
            bluetoothGatt = device.connectGatt(this.ctx, false, mGattCallback);
            // testing if this fix the character reading thing
            bluetoothGatt.connect();

            // TODO when to stop?
            // scanLeDevice(false);// bugbug will stop after first device detection
            //
            // testtest // stopDiscovery(); // bugbug will stop after first device detection
        }
    }


   
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED;
                connectionState = STATE_CONNECTED;
                broadcastUpdate(intentAction);
                Log.i("BluetoothGattCallBack", "Connected to GATT server.");
                // Attempts to discover services after successful connection.
                Log.i("BluetoothGattCallBack", "Attempting to start service discovery:" +
                        bluetoothGatt.discoverServices());

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                connectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
                // broadcastUpdate(intentAction);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // breakpoint
                gattServices = gatt.getServices();
                Log.d("onServicesDiscovered", gattServices.toString());

                for (BluetoothGattService service : gattServices) {

                    List<BluetoothGattCharacteristic> chars = service.getCharacteristics();

                    for (BluetoothGattCharacteristic ch : chars) {

                        //find descriptor UUID that matches Client Characteristic Configuration (0x2902)
                        // and then call setValue on that descriptor

                        setCharacteristicNotification(ch, true);
                        // broadcastUpdate(ACTION_DATA_AVAILABLE, ch);

                    }
                }


                Log.w(TAG, "onServicesDiscovered received success: " + status);
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
            } else {
                // Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            Log.d(TAG, "onCharacteristicRead: " + status);
            Log.d(TAG, "charateristic:" + characteristic.toString());

            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
            Log.d(TAG, "End onCharacteristicRead: " + status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            Log.d(TAG, "onCharacteristicChanged: " ); 
            Log.d(TAG, characteristic.toString());
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
        }
    };

    private void broadcastUpdate(final String action) {
        Log.d("broadcastUpdate", action);
        final Intent intent = new Intent(action);
        // bugbugbugbugbug   (Need to figure out this later)
        if (m_context != null) {
            m_context.sendBroadcast(intent);
        }

    }

    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic) {
                                     
        Log.d("broadcastUpd_realdata", action);
        Log.d("broadcastUpdate", characteristic.toString());
        final Intent intent = new Intent(action);

        // This is special handling for the Heart Rate Measurement profile.  Data parsing is
        // carried out as per profile specifications:
        // http://developer.bluetooth.org/gatt/characteristics/Pages/CharacteristicViewer.aspx?u=org.bluetooth.characteristic.heart_rate_measurement.xml
        if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
            Log.d(TAG, "heart_rate_measurement");
            int flag = characteristic.getProperties();
            int format = -1;
            if ((flag & 0x01) != 0) {
                format = BluetoothGattCharacteristic.FORMAT_UINT16;
                Log.d(TAG, "Heart rate format UINT16.");
            } else {
                format = BluetoothGattCharacteristic.FORMAT_UINT8;
                Log.d(TAG, "Heart rate format UINT8.");
            }
            final int heartRate = characteristic.getIntValue(format, 1);
            Log.d(TAG, String.format("Received heart rate: %d", heartRate));
            intent.putExtra(EXTRA_DATA, String.valueOf(heartRate));
            mheartRateString = String.valueOf(heartRate);
            if (bluetoothGatt != null) {
                if (bluetoothGatt.getDevice() != null)
                    onDeviceFound(bluetoothGatt.getDevice());
            }
        } else {
            // For all other profiles, writes the data formatted in HEX.
            Log.d(TAG, "not heart rate stuff");
            final byte[] data = characteristic.getValue();
            if (data != null && data.length > 0) {
                final StringBuilder stringBuilder = new StringBuilder(data.length);
                for(byte byteChar : data)
                    stringBuilder.append(String.format("%02X ", byteChar));
                intent.putExtra(EXTRA_DATA, new String(data) + "\n" + stringBuilder.toString());
                Log.d(TAG, stringBuilder.toString());
            }
        }


        // bugbugbugbugbug
        if (m_context != null){
            m_context.sendBroadcast(intent);
        }


    }


    public class LocalBinder extends Binder {

        // Log.d("IBinder", "LocalBind");
        BluetoothLEAPI getService() {
            return BluetoothLEAPI.this;
        }
    }

    // bugbugbugbug @Override
    public IBinder onBind(Intent intent) {
        Log.d("IBinder", "onBind");
        return mBinder;
    }

    //bugbugbugbug @Override
    public boolean onUnbind(Intent intent) {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        Log.d(TAG, "onUnBind");
        close();
        return /*super.*/onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        Log.d(TAG, "Initialize");
        BluetoothManager bluetoothManager = (BluetoothManager)ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        if (adapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public boolean connect(final String address) {
        if (adapter == null || address == null) {
            Log.w("connect", "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

       
        // Previously connected device.  Try to reconnect.

        if (bluetoothDeviceAddress != null && address.equals(bluetoothDeviceAddress)
                && bluetoothGatt != null) {
            Log.d("connect", "Trying to use an existing mBluetoothGatt for connection.");
           // if (connectionState == STATE_CONNECTING){
           //     Log.d("connect-bugbug", "skip connecting again");
           //     return true;
           // }
           //
            if (bluetoothGatt.connect()) {
                connectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }


        final BluetoothDevice device = adapter.getRemoteDevice(address);
        if (device == null) {
            Log.w("connect", "Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        bluetoothGatt = device.connectGatt(this.ctx, false, mGattCallback);
        Log.d("connect", "Trying to create a new connection.");
        bluetoothDeviceAddress = address;
        connectionState = STATE_CONNECTING;
        return true;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (adapter == null || bluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        bluetoothGatt.disconnect();
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (bluetoothGatt == null) {
            return;
        }
        bluetoothGatt.close();
        bluetoothGatt = null;
    }

    
    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (adapter == null || bluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        bluetoothGatt.readCharacteristic(characteristic);
        Log.w(TAG, "readCharacteristic");
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (adapter == null || bluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        bluetoothGatt.setCharacteristicNotification(characteristic, enabled);
        Log.w(TAG, "setCaracteristicNotification");
        // This is specific to Heart Rate Measurement.
        if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG));
            Log.d(TAG, "before descriptor setVale");        
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            bluetoothGatt.writeDescriptor(descriptor);
            // Log.d(TAG, "descriptor=", descriptor.toString());
            Log.d(TAG, "after descriptor setVale"); 

        }
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (bluetoothGatt == null) return null;

        return bluetoothGatt.getServices();
    }




    private BluetoothAdapter.LeScanCallback leScanCallback =
        new BluetoothAdapter.LeScanCallback() {
            @Override
            public void onLeScan(final BluetoothDevice device, int rssi,
                                 byte[] scanRecord) {

                Log.d("onLeScan", device.toString());
                // testtest
                connectToDevice(device);
                String address = device.getAddress();

                connect(address);
                onDeviceFound(device);


            }
        };



    private void startDiscovery(long timeout) throws InterruptedException {
        if (adapter == null)
            throw new UnsupportedOperationException("This device has no Bluetooth adapter");

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // TODO
                stopDiscovery();
                // discovering = false;

                // adapter.stopLeScan(leScanCallback);
                stopScanning();
            }
        }, timeout);

        /* TODO
        if (adapter.startDiscovery()) {
            discovering = true;
            return;
        }
        */


        if (scanLeDevice(true)) {
            scanning = true;
           // discovering = true;
            return;
        }



        InteractionCallback callback = ctx.getInteractionCallback();
        if (callback == null)
            throw new UnsupportedOperationException("Bluetooth is disabled and operation is in background");

        if (!callback.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), InteractionCallback.ENABLE_BLUETOOTH))
            throw new InterruptedException("User denied enabling Bluetooth");

        /* TODO
        if (adapter.startDiscovery()) {
            discovering = true;
        }
        */

        //if (scanLeDevice(true)) {
        //    discovering = true;
         //   return;
        // }

    }

    private void stopDiscovery() {
        //discovering = false;
        scanning = false;
        /* TODO
        if (adapter != null)
            adapter.cancelDiscovery();
        */
        scanLeDevice(false);
        /* testtest
        if (adapter != null)
            adapter.cancelDiscovery();
        */
        disconnect();
        close();
    }

    private void stopScanning() {
        try {
            adapter.stopLeScan(leScanCallback);
            Log.d("stopScanning", "Scanning stopped");
        } catch (NullPointerException exception) {
            Log.e("stopScanning", "Can't stop scan. Unexpected NullPointerException", exception);
        }

    }

    /*
    private void pairDevice(String address) throws InterruptedException {
        if (adapter == null)
            throw new UnsupportedOperationException("This device has no Bluetooth adapter");

        BluetoothDevice dev = adapter.getRemoteDevice(address.toUpperCase());

        if (dev.getBondState() == BluetoothDevice.BOND_BONDED)
            return;

        synchronized (this) {
            try {
                while (true) {
                    if (!pairing.containsKey(address)) {
                        pairing.put(address, dev);
                        dev.createBond();
                    } else {
                        dev = pairing.get(address);
                        if (dev.getBondState() == BluetoothDevice.BOND_BONDED)
                            return;
                        if (dev.getBondState() != BluetoothDevice.BOND_BONDING)
                            throw new InterruptedException("User cancelled bonding");
                    }

                    wait();
                }
            } finally {
                pairing.remove(address);
            }
        }
    }
*/
    @NonNull
    private JSONArray readUUIDs(String address) throws InterruptedException {

        if (adapter == null)
            throw new UnsupportedOperationException("This device has no Bluetooth adapter");

        // BluetoothDevice dev = adapter.getRemoteDevice(address.toUpperCase());
        BluetoothDevice dev = bluetoothGatt.getDevice(); // BUGBUG
        address = dev.getAddress().toLowerCase(); //BUGBUG

        long startTime = System.currentTimeMillis();
        synchronized (this) {
            try {

                while (true) {
                    if (!fetchingUuids.containsKey(address)) {
                        fetchingUuids.put(address, dev);
                        if (!dev.fetchUuidsWithSdp())
                            throw new RuntimeException("Fetching UUIDs failed with a generic error");
                    } else {
                        dev = fetchingUuids.get(address);
                        ParcelUuid[] uuids = dev.getUuids();
                        if (uuids != null && uuids.length > 0)
                            return uuidsToJson(uuids);
                    }

                    long now = System.currentTimeMillis();
                    if (FETCH_UUID_TIMEOUT - (now - startTime) < 1) {
                        throw new InterruptedException("Fetching UUIDs timed out");
                    }
                    wait(FETCH_UUID_TIMEOUT - (now - startTime));
                    now = System.currentTimeMillis();
                    if (now - startTime > FETCH_UUID_TIMEOUT) {
                        throw new InterruptedException("Fetching UUIDs timed out");

                    }

                }
                //return readHeartRate();
            } finally {
                fetchingUuids.remove(address);
            }
        }
    }
}
