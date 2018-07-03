package com.example.lezh1k.sensordatacollector;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.SensorManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.printer.AndroidPrinter;
import com.elvishew.xlog.printer.Printer;
import com.elvishew.xlog.printer.file.FilePrinter;
import com.elvishew.xlog.printer.file.backup.FileSizeBackupStrategy;
import com.elvishew.xlog.printer.file.naming.FileNameGenerator;
import com.example.lezh1k.sensordatacollector.Interfaces.MapInterface;
import com.example.lezh1k.sensordatacollector.Presenters.MapPresenter;
import com.felhr.usbserial.UsbSerialInterface;
import com.mapbox.mapboxsdk.Mapbox;
import com.mapbox.mapboxsdk.annotations.Polyline;
import com.mapbox.mapboxsdk.annotations.PolylineOptions;
import com.mapbox.mapboxsdk.camera.CameraPosition;
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.maps.MapView;
import com.mapbox.mapboxsdk.maps.MapboxMap;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import mad.location.manager.lib.Commons.Utils;
import mad.location.manager.lib.Interfaces.ILogger;
import mad.location.manager.lib.Interfaces.LocationServiceInterface;
import mad.location.manager.lib.Loggers.GeohashRTFilter;
import mad.location.manager.lib.SensorAux.SensorCalibrator;
import mad.location.manager.lib.Services.KalmanLocationService;
import mad.location.manager.lib.Services.ServicesHelper;



public class MainActivity extends AppCompatActivity implements LocationServiceInterface , MapInterface, ILogger {
    UsbManager usbManager ;
    UsbDevice device;
    UsbDeviceConnection connection;
    HashMap<String, UsbDevice> usbDevices ;
    private UsbSerialInterface.UsbReadCallback mCallback = new UsbSerialInterface.UsbReadCallback() {
        @Override
        public void onReceivedData(byte[] bytes) {

        }
    };




    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            switch (intent.getAction()) {
                case UsbService.ACTION_USB_PERMISSION_GRANTED: // USB PERMISSION GRANTED
                    Toast.makeText(context, "USB Ready", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_USB_PERMISSION_NOT_GRANTED: // USB PERMISSION NOT GRANTED
                    Toast.makeText(context, "USB Permission not granted", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_NO_USB: // NO USB CONNECTED
                    Toast.makeText(context, "No USB connected", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_USB_DISCONNECTED: // USB DISCONNECTED
                    Toast.makeText(context, "USB disconnected", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_USB_NOT_SUPPORTED: // USB NOT SUPPORTED
                    Toast.makeText(context, "USB device not supported", Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };
    private UsbService usbService;
    private MyHandler mHandler;
    private  final ServiceConnection usbConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName arg0, IBinder arg1) {
            usbService = ((UsbService.UsbBinder)arg1).getService();
            usbService.setHandler(mHandler);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            usbService = null;
        }
    };






    private String xLogFolderPath;
    int flag=0;
    public long timedel,now;
    private static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    class ChangableFileNameGenerator implements FileNameGenerator {
        private String fileName;
        public void setFileName(String fileName) {
            this.fileName = fileName;
        }
        public ChangableFileNameGenerator() {
        }
        @Override
        public boolean isFileNameChangeable() {
            return true;
        }
        @Override
        public String generateFileName(int logLevel, long timestamp) {
            return fileName;
        }
    }

    ChangableFileNameGenerator xLogFileNameGenerator = new ChangableFileNameGenerator();
    public void initXlogPrintersFileName() {
        sdf.setTimeZone(TimeZone.getDefault());
        String dateStr = sdf.format(System.currentTimeMillis());
        String fileName = dateStr;
        final int secondsIn24Hour = 86400; //I don't think that it's possible to press button more frequently
        for (int i = 0; i < secondsIn24Hour; ++i) {
            fileName = String.format("%s_%d", dateStr, i);
            File f = new File(xLogFolderPath, fileName);
            if (!f.exists())
                break;
        }
        xLogFileNameGenerator.setFileName(fileName);
    }

    @Override
    public void log2file(String format, Object... args) {
        XLog.i(format, args);
    }


    class RefreshTask extends AsyncTask {
        boolean needTerminate = false;
        long deltaT;
        Context owner;
        RefreshTask(long deltaTMs, Context owner) {
            this.owner = owner;
            this.deltaT = deltaTMs;
        }

        @Override
        protected Object doInBackground(Object[] objects) {
            while (!needTerminate) {
                try {
                    Thread.sleep(deltaT);
                    publishProgress();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            return null;
        }


        @SuppressLint("DefaultLocale")
        @Override
        protected void onProgressUpdate(Object... values) {
            TextView tvStatus = (TextView) findViewById(R.id.tvStatus);
            TextView tvDistance = (TextView) findViewById(R.id.tvDistance);
            timedel = android.os.SystemClock.elapsedRealtimeNanos() - now;
            now = android.os.SystemClock.elapsedRealtimeNanos();
            double frequency = 1.0e9/timedel;
            if (m_isLogging) {
                if (m_geoHashRTFilter == null)
                    return;
                tvDistance.setText(String.format(
                        "Longitude : %.8f degrees\n"+
                                "Latitude : %.8f degrees\n"+
                                "speed : %f m/s \n" +
                                "direction : %f degrees",
                        m_geoHashRTFilter.displayPoint.Longitude,
                        m_geoHashRTFilter.displayPoint.Latitude,
                        m_geoHashRTFilter.speed,
                        m_geoHashRTFilter.direction
                ));
                 String message = String.format("%.8f%.8f%f%f",m_geoHashRTFilter.displayPoint.Longitude,
                        m_geoHashRTFilter.displayPoint.Latitude,
                        m_geoHashRTFilter.speed,
                        m_geoHashRTFilter.direction);
                 //error is null object reference but how?
                // message="trythis";
             usbService.write(message.getBytes());
            } else {
                if (!m_sensorCalibrator.isInProgress())
                    return;

                tvStatus.setText(m_sensorCalibrator.getCalibrationStatus());
                if (m_sensorCalibrator.getDcAbsLinearAcceleration().isCalculated() &&
                        m_sensorCalibrator.getDcLinearAcceleration().isCalculated()) {
//                    set_isCalibrating(false, false);
                    tvDistance.setText(m_sensorCalibrator.getDcLinearAcceleration().deviationInfoString());
                }
            }
        }
    }
    /*********************************************************/

    private MapPresenter m_presenter;
    private MapboxMap m_map;
    private MapView m_mapView;

    private GeohashRTFilter m_geoHashRTFilter;
    private SensorCalibrator m_sensorCalibrator = null;
    private boolean m_isLogging = false;
    private boolean m_isCalibrating = false;
    private RefreshTask m_refreshTask = new RefreshTask(100, this); //try changing deltaTMs

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
    //start logging location data.
    private void set_isLogging(boolean isLogging) {
        Button btnStartStop = (Button) findViewById(R.id.btnStartStop);
        TextView tvStatus = (TextView) findViewById(R.id.tvStatus);
//        Button btnCalibrate = (Button) findViewById(R.id.btnCalibrate);
        String btnStartStopText;
        String btnTvStatusText;

        if (isLogging) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            m_presenter.stop();
            m_presenter.start();
            m_geoHashRTFilter.stop();
            m_geoHashRTFilter.reset(this); //reset filter
            ServicesHelper.getLocationService(this, value -> {
                if (value.IsRunning()) {
                    return;//if location service is already on, return
                }
                value.stop();//if not running, stop it (what?)
                initXlogPrintersFileName();//what?
                KalmanLocationService.Settings settings =
                        new KalmanLocationService.Settings(Utils.ACCELEROMETER_DEFAULT_DEVIATION,
                                Utils.GPS_MIN_DISTANCE,
                                Utils.GPS_MIN_TIME,
                                Utils.GEOHASH_DEFAULT_PREC,
                                Utils.GEOHASH_DEFAULT_MIN_POINT_COUNT,
                                Utils.SENSOR_DEFAULT_FREQ_HZ,
                                this, false, Utils.DEFAULT_VEL_FACTOR, Utils.DEFAULT_POS_FACTOR);
                value.reset(settings,m_geoHashRTFilter); //warning!! here you can adjust your filter behavior
                value.start();
            });

            btnStartStopText = "Stop tracking";
            btnTvStatusText = "Tracking is in progress";

        } else {
            btnStartStopText = "Start tracking";
            btnTvStatusText = "Paused";
            m_presenter.stop();
            ServicesHelper.getLocationService(this, value -> {
                value.stop();
            });
        }

        if (btnStartStop != null)
            btnStartStop.setText(btnStartStopText);
        if (tvStatus != null)
            tvStatus.setText(btnTvStatusText);

//        btnCalibrate.setEnabled(!isLogging);
        m_isLogging = isLogging;
    }


    public void btnStartStop_click(View v) {
        set_isLogging(!m_isLogging);
    }

    //begin stuff
    private void initActivity() {

        String[] interestedPermissions;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
            interestedPermissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        } else {
            interestedPermissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
        }

        ArrayList<String> lstPermissions = new ArrayList<>(interestedPermissions.length);
        for (String perm : interestedPermissions) {
            if (ActivityCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                lstPermissions.add(perm);
            }
        }

        if (!lstPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, lstPermissions.toArray(new String[0]),
                    100);
        }
        //get sensors and gps service
        SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        if (sensorManager == null || locationManager == null) {
            System.exit(1);//handle problem
        }

        m_sensorCalibrator = new SensorCalibrator(sensorManager);//initialize caliberator
        ServicesHelper.getLocationService(this, value -> {
            set_isLogging(value.IsRunning());//begin location logging
        });
        //set_isCalibrating(false, true);//start caliberating when user says so.
    }

    //uncaught exceptions
    private Thread.UncaughtExceptionHandler defaultUEH;
    // handler listener
    private Thread.UncaughtExceptionHandler _unCaughtExceptionHandler = new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread thread, Throwable ex) {
            try {
                XLog.i("UNHANDLED EXCEPTION: %s, stack : %s", ex.toString(), ex.getStackTrace());
            } catch (Exception e) {
                Log.i("SensorDataCollector", String.format("Megaunhandled exception : %s, %s, %s",
                        e.toString(), ex.toString(), ex.getStackTrace()));
            }
            defaultUEH.uncaughtException(thread, ex);
        }
    };

    @Override
    public void locationChanged(Location location) {
        if (m_map != null && m_presenter != null)
        {
            if (!m_map.isMyLocationEnabled()) {
                m_map.setMyLocationEnabled(true);
                m_map.getMyLocationViewSettings().setForegroundTintColor(ContextCompat.getColor(this, R.color.red));
            }

            m_presenter.locationChanged(location, m_map.getCameraPosition());

        }
    }

    public static final int FILTER_KALMAN_ONLY = 0;
    public static final int FILTER_KALMAN_WITH_GEO = 1;
    public static final int GPS_ONLY = 2;
    private int routeColors[] = {R.color.mapbox_blue, R.color.colorAccent, R.color.green};
    private int routeWidths[] = {1, 3, 1};
    private Polyline lines[] = new Polyline[3];

    @Override
    public void showRoute(List<LatLng> route, int interestedRoute) {

        CheckBox cbGps, cbFilteredKalman, cbFilteredKalmanGeo;
        cbGps = (CheckBox) findViewById(R.id.cbGPS);
        cbFilteredKalman = (CheckBox) findViewById(R.id.cbFilteredKalman);
        cbFilteredKalmanGeo = (CheckBox) findViewById(R.id.cbFilteredKalmanGeo);
        boolean enabled[] = {cbFilteredKalman.isChecked(), cbFilteredKalmanGeo.isChecked(), cbGps.isChecked()};
        if (m_map != null) {
            runOnUiThread(() ->
                    m_mapView.post(() -> {
                        if (lines[interestedRoute] != null)
                            m_map.removeAnnotation(lines[interestedRoute]);

                        if (!enabled[interestedRoute])
                            route.clear(); //too many hacks here

                        lines[interestedRoute] = m_map.addPolyline(new PolylineOptions()
                                .addAll(route)
                                .color(ContextCompat.getColor(this, routeColors[interestedRoute]))
                                .width(routeWidths[interestedRoute]));
                    }));
        }
    }

    @Override
    public void moveCamera(CameraPosition position) {
        runOnUiThread(() ->
                m_mapView.postDelayed(() -> {
                    if (m_map != null) {
                        m_map.animateCamera(CameraUpdateFactory.newCameraPosition(position));
                    }
                }, 100));
    }

    @Override
    public void setAllGesturesEnabled(boolean enabled) {
        if (enabled) {
            m_mapView.postDelayed(() -> {
                m_map.getUiSettings().setScrollGesturesEnabled(true);
                m_map.getUiSettings().setZoomGesturesEnabled(true);
                m_map.getUiSettings().setDoubleTapGesturesEnabled(true);
            }, 500);
        } else {
            m_map.getUiSettings().setScrollGesturesEnabled(false);
            m_map.getUiSettings().setZoomGesturesEnabled(false);
            m_map.getUiSettings().setDoubleTapGesturesEnabled(false);
        }
    }

    public void setupMap(@Nullable Bundle savedInstanceState) {
        m_mapView = (MapView) findViewById(R.id.mapView);
        m_mapView.onCreate(savedInstanceState);

        m_presenter = new MapPresenter(this, this, m_geoHashRTFilter);
        m_mapView.getMapAsync(mapboxMap -> {
            m_map = mapboxMap;
            m_map.setStyleUrl(BuildConfig.lightMapStyle);

            m_map.getUiSettings().setLogoEnabled(false);
            m_map.getUiSettings().setAttributionEnabled(false);
            m_map.getUiSettings().setTiltGesturesEnabled(false);

            int leftMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());
            int topMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 56, getResources().getDisplayMetrics());
            int rightMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());
            int bottomMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());
            m_map.getUiSettings().setCompassMargins(leftMargin, topMargin, rightMargin, bottomMargin);
            ServicesHelper.addLocationServiceInterface(this);
            m_presenter.getRoute();
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ServicesHelper.addLocationServiceInterface(this);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        usbDevices = usbManager.getDeviceList();
        defaultUEH = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(_unCaughtExceptionHandler);
        Mapbox.getInstance(this, BuildConfig.access_token);
        setContentView(R.layout.activity_main);
        m_geoHashRTFilter = new GeohashRTFilter(Utils.GEOHASH_DEFAULT_PREC, Utils.GEOHASH_DEFAULT_MIN_POINT_COUNT);
        setupMap(savedInstanceState);

        //comment this out entirely in the final code
        CheckBox cbGps, cbFilteredKalman, cbFilteredKalmanGeo;
        cbGps = (CheckBox) findViewById(R.id.cbGPS);
        cbFilteredKalman = (CheckBox) findViewById(R.id.cbFilteredKalman);
        cbFilteredKalmanGeo = (CheckBox) findViewById(R.id.cbFilteredKalmanGeo);
        CheckBox cb[] = {cbFilteredKalman, cbFilteredKalmanGeo, cbGps};
        for (int i = 0; i < 3; ++i) {
            if (cb[i] == null)
                continue;
            cb[i].setBackgroundColor(ContextCompat.getColor(this, routeColors[i]));
        }
        File esd = Environment.getExternalStorageDirectory();
        String storageState = Environment.getExternalStorageState();
        if (storageState != null && storageState.equals(Environment.MEDIA_MOUNTED)) {
            xLogFolderPath = String.format("%s/%s/", esd.getAbsolutePath(), "SensorDataCollector");
            Printer androidPrinter = new AndroidPrinter();             // Printer that print the log using android.util.Log
            initXlogPrintersFileName();
            Printer xLogFilePrinter = new FilePrinter
                    .Builder(xLogFolderPath)
                    .fileNameGenerator(xLogFileNameGenerator)
                    .backupStrategy(new FileSizeBackupStrategy(1024 * 1024 * 100)) //100MB for backup files
                    .build();
            XLog.init(LogLevel.ALL, androidPrinter, xLogFilePrinter);
        } else {
            //todo set some status
        }
        mHandler = new MyHandler(this);

        //  usbService.changeBaudRate(115200);



    }

    @Override
    protected void onStart() {
        super.onStart();
        initActivity();
        if (m_mapView != null) {
            m_mapView.onStart();//begin mapview
        }
        m_sensorCalibrator.reset();
        m_sensorCalibrator.start();
        if(m_sensorCalibrator.getCalibrationStatus()=="abs:100%, lin::100%")
        {
            m_sensorCalibrator.stop();
            flag=1;
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (m_mapView != null) {
            m_mapView.onStop();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setFilters();
        startService(UsbService.class,usbConnection, null);
        if (m_mapView != null) {
            m_mapView.onResume();
        }
        m_refreshTask = new RefreshTask(100, this); // change the time here.
        m_refreshTask.needTerminate = false;
        m_refreshTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (m_mapView != null) {
            m_mapView.onPause();
        }
        unregisterReceiver(mUsbReceiver);
        unbindService(usbConnection);
        m_refreshTask.needTerminate = true;
        m_refreshTask.cancel(true);
        if (m_sensorCalibrator != null) {
            m_sensorCalibrator.stop();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (m_mapView != null) {
            m_mapView.onSaveInstanceState(outState);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (m_mapView != null) {
            m_mapView.onLowMemory();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (m_mapView != null) {
            m_mapView.onDestroy();
        }
    }
    private static class MyHandler extends Handler {
        private final WeakReference<MainActivity> mActivity;

        public MyHandler(MainActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

//        @Override
//        public void handleMessage(Message msg) {
//            switch (msg.what) {
//                case UsbService.MESSAGE_FROM_SERIAL_PORT:
//                    String data = (String) msg.obj;
//                    mActivity.get().display.append(data);
//                    break;
//                case UsbService.CTS_CHANGE:
//                    Toast.makeText(mActivity.get(), "CTS_CHANGE",Toast.LENGTH_LONG).show();
//                    break;
//                case UsbService.DSR_CHANGE:
//                    Toast.makeText(mActivity.get(), "DSR_CHANGE",Toast.LENGTH_LONG).show();
//                    break;
//                case UsbService.SYNC_READ:
//                    String buffer = (String) msg.obj;
//                    mActivity.get().display.append(buffer);
//                    break;
//            }
//        }
    }

    private void startService(Class<?> service, ServiceConnection serviceConnection, Bundle extras) {
        if (!UsbService.SERVICE_CONNECTED) {
            Intent startService = new Intent(this, service);
            if (extras != null && !extras.isEmpty()) {
                Set<String> keys = extras.keySet();
                for (String key : keys) {
                    String extra = extras.getString(key);
                    startService.putExtra(key, extra);
                }
            }
            startService(startService);
        }
        Intent bindingIntent = new Intent(this, service);
        bindService(bindingIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void setFilters() {
        IntentFilter filter = new IntentFilter();

        filter.addAction(UsbService.ACTION_USB_PERMISSION_GRANTED);
        filter.addAction(UsbService.ACTION_NO_USB);
        filter.addAction(UsbService.ACTION_USB_DISCONNECTED);
        filter.addAction(UsbService.ACTION_USB_NOT_SUPPORTED);
        filter.addAction(UsbService.ACTION_USB_PERMISSION_NOT_GRANTED);
        registerReceiver(mUsbReceiver, filter);
    }

}
