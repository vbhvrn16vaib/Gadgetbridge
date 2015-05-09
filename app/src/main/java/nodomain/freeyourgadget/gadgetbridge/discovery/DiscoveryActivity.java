package nodomain.freeyourgadget.gadgetbridge.discovery;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.util.ArrayList;

import nodomain.freeyourgadget.gadgetbridge.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.DeviceHelper;
import nodomain.freeyourgadget.gadgetbridge.GB;
import nodomain.freeyourgadget.gadgetbridge.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.adapter.DeviceCandidateAdapter;

public class DiscoveryActivity extends Activity implements AdapterView.OnItemClickListener {
    private static final String TAG = "DiscoveryAct";
    private static final long SCAN_DURATION = 60000; // 60s

    private Handler handler = new Handler();

    private BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case BluetoothAdapter.ACTION_DISCOVERY_STARTED:
                    discoveryStarted(Scanning.SCANNING_BT);
                    break;
                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                    // continue with LE scan, if available
                    if (GB.supportsBluetoothLE()) {
                        startBTLEDiscovery();
                    } else {
                        discoveryFinished();
                    }
                    break;
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    int oldState = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.STATE_OFF);
                    int newState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
                    bluetoothStateChanged(oldState, newState);
                    break;
                case BluetoothDevice.ACTION_FOUND:
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, GBDevice.RSSI_UNKNOWN);
                    handleDeviceFound(device, rssi);
                    break;
            }
        }
    };

    private BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            handleDeviceFound(device, (short) rssi);
        }
    };

    private Runnable stopRunnable = new Runnable() {
        @Override
        public void run() {
            stopDiscovery();
        }
    };

    private ProgressBar progressView;
    private BluetoothAdapter adapter;
    private ArrayList<DeviceCandidate> deviceCandidates = new ArrayList<>();
    private ListView deviceCandidatesView;
    private DeviceCandidateAdapter cadidateListAdapter;
    private Button startButton;
    private Scanning isScanning = Scanning.SCANNING_OFF;

    private enum Scanning {
        SCANNING_BT,
        SCANNING_BTLE,
        SCANNING_OFF
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_discovery);
        startButton = (Button) findViewById(R.id.discovery_start);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onStartButtonClick(startButton);
            }
        });

        progressView = (ProgressBar) findViewById(R.id.discovery_progressbar);
        progressView.setProgress(0);
        progressView.setIndeterminate(true);
        progressView.setVisibility(View.GONE);
        deviceCandidatesView = (ListView) findViewById(R.id.discovery_deviceCandidatesView);

        cadidateListAdapter = new DeviceCandidateAdapter(this, deviceCandidates);
        deviceCandidatesView.setAdapter(cadidateListAdapter);
        deviceCandidatesView.setOnItemClickListener(this);

        IntentFilter bluetoothIntents = new IntentFilter();
        bluetoothIntents.addAction(BluetoothDevice.ACTION_FOUND);
        bluetoothIntents.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        bluetoothIntents.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        bluetoothIntents.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);

        registerReceiver(bluetoothReceiver, bluetoothIntents);

        startDiscovery();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelableArrayList("deviceCandidates", deviceCandidates);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        ArrayList<Parcelable> restoredCandidates = savedInstanceState.getParcelableArrayList("deviceCandidates");
        if (restoredCandidates != null) {
            deviceCandidates.clear();
            for (Parcelable p : restoredCandidates) {
                deviceCandidates.add((DeviceCandidate) p);
            }
        }
    }

    public void onStartButtonClick(View button) {
        Log.d(TAG, "Start Button clicked");
        if (isScanning()) {
            stopDiscovery();
        } else {
            startDiscovery();
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(bluetoothReceiver);
        super.onDestroy();
    }

    private void handleDeviceFound(BluetoothDevice device, short rssi) {
        DeviceCandidate candidate = new DeviceCandidate(device, (short) rssi);
        if (DeviceHelper.getInstance().isSupported(candidate)) {
            int index = deviceCandidates.indexOf(candidate);
            if (index >= 0) {
                deviceCandidates.set(index, candidate); // replace
            } else {
                deviceCandidates.add(candidate);
            }
            cadidateListAdapter.notifyDataSetChanged();
        }
    }

    /**
     * Pre: bluetooth is available, enabled and scanning is off.
     * Post: BT is discovering
     */
    private void startDiscovery() {
        if (isScanning()) {
            Log.w(TAG, "Not starting BLE discovery, because already scanning.");
            return;
        }

        Log.i(TAG, "Starting discovery...");
        discoveryStarted(Scanning.SCANNING_BT); // just to make sure
        if (ensureBluetoothReady()) {
            startBTDiscovery();
        } else {
            discoveryFinished();
            Toast.makeText(this, "Enable Bluetooth to discover devices.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isScanning() {
        return isScanning != Scanning.SCANNING_OFF;
    }

    private void stopDiscovery() {
        Log.i(TAG, "Stopping discovery");
        if (isScanning()) {
            if (isScanning == Scanning.SCANNING_BT) {
                stopBTDiscovery();
            } else if (isScanning == Scanning.SCANNING_BTLE) {
                stopBTLEDiscovery();
            }
            handler.removeMessages(0, stopRunnable);
            // unfortunately, we never get a call back when stopping the scan, so
            // we do it manually:
            discoveryFinished();
        }
    }

    private void stopBTLEDiscovery() {
        adapter.stopLeScan(leScanCallback);
    }

    private void stopBTDiscovery() {
        adapter.cancelDiscovery();
    }

    private void bluetoothStateChanged(int oldState, int newState) {
        discoveryFinished();
        startButton.setEnabled(newState == BluetoothAdapter.STATE_ON);
    }

    private void discoveryFinished() {
        isScanning = Scanning.SCANNING_OFF;
        progressView.setVisibility(View.GONE);
        startButton.setText(getString(R.string.discovery_start_scanning));
    }

    private void discoveryStarted(Scanning what) {
        isScanning = what;
        progressView.setVisibility(View.VISIBLE);
        startButton.setText(getString(R.string.discovery_stop_scanning));
    }

    private boolean ensureBluetoothReady() {
        boolean available = checkBluetoothAvailable();
        startButton.setEnabled(available);
        if (available) {
            adapter.cancelDiscovery();
            // must not return the result of cancelDiscovery()
            // appears to return false when currently not scanning
            return true;
        }
        return false;
    }

    private boolean checkBluetoothAvailable() {
        BluetoothManager bluetoothService = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bluetoothService == null) {
            Log.w(TAG, "No bluetooth available");
            this.adapter = null;
            return false;
        }
        BluetoothAdapter adapter = bluetoothService.getAdapter();
        if (!adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth not enabled");
            this.adapter = null;
            return false;
        }
        this.adapter = adapter;
        return true;
    }

    private void startBTLEDiscovery() {
        handler.removeMessages(0, stopRunnable);
        handler.postDelayed(stopRunnable, SCAN_DURATION);
        adapter.startLeScan(leScanCallback);
    }

    private void startBTDiscovery() {
        handler.removeMessages(0, stopRunnable);
        handler.postDelayed(stopRunnable, SCAN_DURATION);
        adapter.startDiscovery();
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        DeviceCandidate deviceCandidate = deviceCandidates.get(position);
        if (deviceCandidate == null) {
            Log.e(TAG, "Device candidate clicked, but item not found");
            return;
        }

        DeviceCoordinator coordinator = DeviceHelper.getInstance().getCoordinator(deviceCandidate);
        Intent intent = new Intent(this, coordinator.getPairingActivity());
        intent.putExtra(DeviceCoordinator.EXTRA_DEVICE_MAC_ADDRESS, deviceCandidate.getMacAddress());
        startActivity(intent);
    }
}
