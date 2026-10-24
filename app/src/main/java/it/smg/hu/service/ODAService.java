package it.smg.hu.service;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.SurfaceView;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import it.smg.hu.config.Settings;
import it.smg.hu.manager.HondaConnectManager;
import it.smg.hu.manager.USBManager;
import it.smg.hu.manager.WIFIManager;
import it.smg.hu.projection.InputDevice;
import it.smg.hu.ui.notification.NotificationFactory;
import it.smg.libs.aasdk.tcp.TCPConnectException;
import it.smg.libs.common.Log;
import it.smg.libs.aasdk.service.AndroidAutoEntity;
import it.smg.libs.aasdk.service.IAndroidAutoEntityEventHandler;
import it.smg.libs.aasdk.tcp.TCPEndpoint;
import it.smg.libs.aasdk.usb.LibUsbDevice;

public class ODAService extends Service implements IAndroidAutoEntityEventHandler {

    public static final String START_ACTION = "it.smg.hu.service.ODAService.START_ACTION";
    public static final String STOP_ACTION = "it.smg.hu.service.ODAService.STOP_ACTION";
    public static final String STOP_VIDEO_INDICATION = "it.smg.hu.service.ODAService.STOP_VIDEO_INDICATION";
    public static final String FORCE_CLOSE_ACTION = "it.smg.hu.service.ODAService.FORCE_CLOSE_ACTION";

    public static final String EXTRA_START_MODE = "startMode";
    public static final String MODE_USB = "modeUSB";
    public static final String MODE_WIFI = "modeWifi";

    private static final String TAG = "ODAService";

    private final IBinder mBinder = new ServiceBinder();

    private LocalBroadcastManager localBroadcastManager_;
    private NotificationFactory notificationFactory_;
    private AndroidAutoEntity androidAutoEntity_;

    private  USBManager usbManager_;
    private  WIFIManager wifiManager_;

    private Thread startThread_;

    private Handler mainHandler_;

    private volatile boolean isRunning_;
    private boolean isStopping_;

    public ODAService() {}

    @Override
    public void onCreate() {
        if (Log.isInfo()) Log.i(TAG, "create");
        super.onCreate();

        NotificationFactory.init(getApplicationContext());
        notificationFactory_ = NotificationFactory.instance();

        usbManager_ = USBManager.instance();
        wifiManager_ = WIFIManager.instance();
        mainHandler_ = new Handler(Looper.getMainLooper());

        localBroadcastManager_ = LocalBroadcastManager.getInstance(this);
    }

    public synchronized void startUsb(SurfaceView surfaceView, InputDevice.OnKeyHolder keyHolder){
        if (startThread_ != null || isRunning_ || isStopping_) {
            if (Log.isInfo()) Log.i(TAG, "Ignoring duplicate USB start");
            return;
        }
        startThread_ = new Thread(() -> {
            Looper.prepare();

            if (usbManager_.aoapDevice() != null) {
                isRunning_ = true;

                if (Log.isVerbose()) Log.v(TAG, "aoap device available, start in usb mode");
                try {
                    LibUsbDevice device = usbManager_.aoapDevice();
                    if (device.open()) {
                        if (Log.isInfo()) Log.i(TAG, "device opened");
                        androidAutoEntity_ = AndroidAutoEntityFactory.create(this, device, surfaceView, keyHolder);
                        androidAutoEntity_.start(this);
                    } else {
                        Log.e(TAG, "Error in open usb device");
                        onAndroidAutoQuitOnError("USB OPEN DEVICE", -1);
                        return;
                    }
                } catch (Exception e){
                    Log.e(TAG, "error", e);
                    onAndroidAutoQuitOnError("USB GENERIC ERROR", -1);
                    return;
                }
            }
            synchronized (ODAService.this) {
                if (!isRunning_) startThread_ = null;
            }
            if (Log.isInfo()) Log.i(TAG, "start usb thead completed");
        });
        startThread_.start();
    }

    public synchronized void startWifi(SurfaceView surfaceView, InputDevice.OnKeyHolder keyHolder){
        if (startThread_ != null || isRunning_ || isStopping_) {
            if (Log.isInfo()) Log.i(TAG, "Ignoring duplicate WiFi start");
            return;
        }
        startThread_ = new Thread(() -> {
            Looper.prepare();

            try {
                String ipAddress = wifiManager_.getIpAddress();
                if (ipAddress != null) {
                    isRunning_ = true;

//                    if (Log.isInfo()) Log.i(TAG, "Connect to ip " + ipAddress);
                    TCPEndpoint tcpEndpoint = new TCPEndpoint(ipAddress);
                    androidAutoEntity_ = AndroidAutoEntityFactory.create(this, tcpEndpoint, surfaceView, keyHolder);
                    androidAutoEntity_.start(this);
                }
                synchronized (ODAService.this) {
                    if (!isRunning_) startThread_ = null;
                }
                if (Log.isInfo()) Log.i(TAG, "start wifi thead completed");
            } catch (TCPConnectException e){
                Log.e(TAG, "TCP Connection error", e);
                stop();
            } catch (RuntimeException e) {
                Log.e(TAG, "WiFi session startup error", e);
                onAndroidAutoQuitOnError("WIFI GENERIC ERROR", -1);
            }
        });
        startThread_.start();
    }

    public void shutdown(){
        if (androidAutoEntity_ != null) {
            androidAutoEntity_.shutdown();
        }
    }

    public void releaseFocus(){
        if (androidAutoEntity_ != null) {
            androidAutoEntity_.releaseFocus();
        }
    }

    public void gainFocus(){
        if (androidAutoEntity_ != null) {
            androidAutoEntity_.gainFocus();
        }
    }

    public void stop(){
        // Claim shutdown once, without holding the monitor during native teardown:
        // JNI callbacks can re-enter stop() from another thread.
        synchronized (this) {
            if (!isRunning_ || isStopping_) {
                if (Log.isInfo()) Log.i(TAG, "service not running or already stopping");
                return;
            }
            isStopping_ = true;
            isRunning_ = false;
        }

        if (Log.isInfo()) Log.i(TAG, "Stop");

        // Detach the entity before native teardown so reentrant callbacks
        // cannot observe a reference that is already being destroyed.
        AndroidAutoEntity entity;
        synchronized (this) {
            entity = androidAutoEntity_;
            androidAutoEntity_ = null;
        }
        try {
            try {
                if (Settings.instance().advanced.hondaIntegrationEnabled()) {
                    HondaConnectManager.instance().endAudioBinding();
                }
            } finally {
                if (entity != null) {
                    try {
                        entity.stop();
                    } finally {
                        entity.delete();
                    }
                }
            }
        } finally {
            synchronized (this) {
                startThread_ = null;
            }
            Intent stopIntent = new Intent(ODAService.STOP_ACTION);
            localBroadcastManager_.sendBroadcast(stopIntent);

            Intent service = new Intent(this, ODAService.class);
            stopService(service);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void onDestroy() {
        if (Log.isDebug()) Log.d(TAG, "onDestroy");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (Log.isDebug()) Log.d(TAG, "onUnbind");
        return super.onUnbind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Log.isDebug()) Log.d(TAG, "Received start id " + startId + ": " + intent);

        Notification notification = notificationFactory_.create();
        startForeground(123456, notification);

//        return START_NOT_STICKY;
        return START_STICKY;
    }

    @Keep
    @Override
    public void onAndroidAutoQuit() {
        stop();
    }

    @Keep
    @Override
    public void onAndroidAutoQuitOnError(String error, int nativeErrorCode){
        Log.e(TAG, "closing with error " + error + "(" + nativeErrorCode + ")");

        mainHandler_.post(() -> {
            Toast.makeText(this, "Closed due to " + error + " error", Toast.LENGTH_LONG).show();
        });

        stop();
    }

    @Keep
    @Override
    public void onAVChannelStopIndication() {
        if (Log.isInfo()) Log.i(TAG, "stop video indication");
        Intent stopIntent = new Intent(ODAService.STOP_VIDEO_INDICATION);
        localBroadcastManager_.sendBroadcast(stopIntent);
    }

    public class ServiceBinder extends Binder {
        public ODAService getService() {
            return ODAService.this;
        }
    }

}
