package net.garyscorner.simplemarinegps;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.Manifest;
import android.widget.TextView;

import org.w3c.dom.Text;

import java.security.Permission;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.Timer;
import java.util.TimerTask;

import static java.lang.Math.abs;
import static java.lang.Math.floor;


public class MainActivity extends AppCompatActivity {


    //declare vars
    private LocationManager locationmanager;
    private LocationListener locationlistener;


    static String appName;

    private long minUpdateTime;  //min between location updates milliseconds
    private long minDistance;  //minimum distance between updates in meters

    private int warnAccuracyThreshold; //accuracy before warning
    private int maxAccuracyThreshold;  //maxAccuracy before error

    private long warnTimeThreshold;  //Mark time is warning after this
    private long maxTimeThreshold;  //Mark time as error after this

    private final int requestGPScode = 1;  //return code for GPS permissions gran/deny

    long lastLocationTime = 0;


    private final long updateLastGPSperiod = 5 * 1000;
    Timer timer;
    TimerTask timertask;
    private Runnable updateLastGPSRunable;




    //widgets
    TextView text_lat, text_long, text_acc, text_last, text_status;
    MenuItem action_requestPermissions;

    //code
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        appName = getResources().getString(R.string.app_name_log);

        Log.d(appName, "Creating main activity!");

        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        //set textview widget variables
        text_lat = (TextView) findViewById(R.id.text_lat);
        text_long = (TextView) findViewById(R.id.text_long);
        text_acc = (TextView) findViewById(R.id.text_acc);
        text_last = (TextView) findViewById(R.id.text_last);
        text_status = (TextView) findViewById(R.id.text_status);

        //set inital text_status color
        setTextViewBad(text_status);

        //setup location manager and listener
        locationsetup();

        //request permissions or starting will be handled onStart
        requestLocationPermissions();



    }


    //restreive shared prefferences
    private void setSharedPrefs() {
        //get the shared preferences  and set them are variables

        Log.d(appName, "Getting shard preferences");
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        minUpdateTime = 1000 * Long.valueOf(preferences.getString("minUpdateTime", getResources().getString(R.string.pref_header_general_minUpdateTime_default)));
        minDistance = Long.valueOf(preferences.getString("minDistance", getResources().getString(R.string.pref_header_general_minDistance_default)));

        warnAccuracyThreshold = Integer.valueOf( preferences.getString("warnAccuracyThreshold", getResources().getString(R.string.pref_header_general_warnAccuracyThreshold_default)) );
        maxAccuracyThreshold = Integer.valueOf(preferences.getString("maxAccuracyThreshold", getResources().getString(R.string.pref_header_general_maxAccuracyThreshold_default)));

        warnTimeThreshold = Long.valueOf( preferences.getString("warnTimeThreshold", getResources().getString(R.string.pref_header_general_warnTimeThreshold_default))  ) * 60;
        maxTimeThreshold = Long.valueOf( preferences.getString( "maxTimeThreshold", getResources().getString(R.string.pref_header_general_maxTimeThreshold_default) ) ) * 60;


        //output preferences
        Log.d(appName, "Preferences: minUpdateTime(" + Long.toString(minUpdateTime) + ") minDistance(" + Long.toString(minDistance) + ") warnAccuracyThreshold(" + Integer.toString(warnAccuracyThreshold) + ") maxAccuracyThreshold(" + Integer.toString(maxAccuracyThreshold) + ") warnTimeThreshold(" + Long.toString(warnTimeThreshold) + ") maxTimeThreshold(" + Long.toString(maxTimeThreshold) + ")");

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        //set actionbar menu widgets to variables
        action_requestPermissions = (MenuItem) menu.findItem(R.id.action_requestPermissions);


        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            Intent settingsintent = new Intent(this, Settings.class);
            startActivity(settingsintent);
            return true;
        } else if(id == R.id.action_requestPermissions) {
            requestLocationPermissions();
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onStart() {
        super.onStart();  //call super first

        setSharedPrefs();

        Log.d(appName, "Starting main activity");


        //setup runable object for timertask so we dont create it every time
        updateLastGPSRunable = new Runnable() {
            @Override
            public void run() {
                long timeDiff = (System.currentTimeMillis() - lastLocationTime) / 1000;

                if(timeDiff < 5 ) {
                  text_last.setText("Now");
                } else if(timeDiff < 60) {
                    text_last.setText(Long.toString(timeDiff) + " secs");
                } else {
                    text_last.setText(Long.toString(timeDiff/60) + " mins");
                }

                if(maxTimeThreshold < timeDiff ) {
                    setTextViewBad(text_last);
                } else if( warnTimeThreshold < timeDiff ) {
                    setTextViewWarn(text_last);
                } else {
                    setTextViewGood(text_last);
                }

            }

        };

        updateLastGPSRunable.run();  //run as soon as we open

        //start the timer to update last time
        timer = new Timer();

        timertask = new TimerTask() {
            @Override
            public void run() {

                if( lastLocationTime > 0 ) { //only perform if we have received a location

                    runOnUiThread(updateLastGPSRunable);

                }
            }
        };

        timer.schedule(timertask, 0, updateLastGPSperiod);

        //if we have permissions go ahead and start GPS
        if(checkLocationPermission()) {
            startLocationManager();
        }

    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(appName, "Location manager paused");
    }

    @Override
    public void onStop() {
        super.onStop();

        Log.d(appName, "Main activity stopped");

        timer.cancel();

        stopLocationServices();


    }

    public void onRequestPermissionsResult(int requestCode,  String permissions[], int[] grantResults) {
        switch (requestCode) {

            case requestGPScode: {

                    //start the location manager if not enabled, also change the status pf action_requestPermission to be visable or invisable depending
                    if(grantResults.length >0  && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        action_requestPermissions.setVisible(false);
                        startLocationManager();
                    } else {
                        action_requestPermissions.setVisible(true);
                    }

                    return;
            }


        }



    }


    //check for location manager permissions
    public boolean checkLocationPermission() {

        boolean permissions = PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION);


        return permissions;
    }


    //request location permission if we dont have it
    private boolean requestLocationPermissions() {
        if(!checkLocationPermission()) {

            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, requestGPScode);

            return false;  //return false if we dont have permissions but have requested
        } else {
            return true;  //return true if we already have permissions
        }
    }

    //handle location change  returns false if location is null
    private boolean processLocation(Location location) {

        if(location != null) {
            Log.d(appName, "Location updated" + location.toString());


            text_lat.setText(doubleToLat(location.getLatitude()));
            text_long.setText(doubleToLong(location.getLongitude()));
            text_acc.setText(doubleToAcc(location.getAccuracy()));

            //set the color to indicate location accuracy
            if (maxAccuracyThreshold < location.getAccuracy()) {
                setTextViewBad(text_acc);
            } else if( warnAccuracyThreshold < location.getAccuracy() ) {
                setTextViewWarn(text_acc);
            }else {
                setTextViewGood(text_acc);
            }

            lastLocationTime = location.getTime();

            //set last update time
            setTextViewGood(text_last);
            text_last.setText("Now");

            return true;

        } else {
            Log.d(appName, "Location returned NULL, no update required.");
            return false;
        }
    }

    //setup locaiton manager and listener
    private void locationsetup() {

        locationmanager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        locationlistener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                processLocation(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
                Log.d(appName, "LocationManager status change");
            }

            @Override
            public void onProviderEnabled(String provider) {
                Log.d(appName, "LocationManager enabled");
                text_status.setText("enabled");
                setTextViewGood(text_status);
            }

            @Override
            public void onProviderDisabled(String provider) {
                Log.d(appName, "LocationManager disabled");
                text_status.setText("disabled");
                setTextViewBad(text_status);
            }
        };

    }

    private String doubleToLat(double lat) {

        double unsignedLat = lat;
        int degrees = (int)floor(unsignedLat);
        double minutes = ( unsignedLat - degrees ) * 60;

        char northSouth;

        if(lat < 0) {
            northSouth = 'S';
        } else {
            northSouth = 'N';
        }

        return "" + degrees + "\u00B0 " + (Math.round(minutes * 100.0) / 100.0) + "' " + northSouth;

    }

    private String doubleToLong(double longitude) {

        double unsignedLong = abs(longitude);
        int degrees = (int)floor(unsignedLong);
        double minutes = (unsignedLong - degrees) * 60;

        char eastWest;
        if(longitude < 0) {
            eastWest = 'W';
        } else {
            eastWest = 'E';
        }

        return "" + degrees + "\u00B0 " + (Math.round(minutes * 100.0) / 100.0) + "' " + eastWest;

    }

    private String doubleToAcc(double acc) {
        return Double.toString(acc)+"m";
    }


    private boolean stopLocationServices() { //stop locaiton services

        Log.d(appName, "Attempting to remove locaiton updates");
        try {
            locationmanager.removeUpdates(locationlistener);
        } catch (SecurityException e) {
            Log.w(appName, "Removing location update failed!?!");
            return false;
        }


        return true;
    }

    //star location services
    private void startLocationManager() {
        Log.d(appName, "Attempting to start location manager");

        try{

            //try to get last location print debug info if none found
            if( !processLocation( (Location) locationmanager.getLastKnownLocation(LocationManager.GPS_PROVIDER) ) ) {
                Log.d(appName, "Could not get lastKnownLocation");
            }
            locationmanager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minUpdateTime, minDistance, locationlistener);

        } catch (SecurityException e) {  //We should already havechecked permissions at this point but if something happens handle
            Log.d(appName, "No permissions to start locationmanager unexpectidly");

        }

        if(locationmanager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            setTextViewGood(text_status);
            text_status.setText("enabled");
        } else {
            setTextViewBad(text_status);
            text_status.setText("disabled");
        }

    }

    private void setTextViewGood(TextView textview) {
        textview.setBackgroundColor(Color.GREEN);
    }

    private void setTextViewWarn(TextView textview) {
        textview.setBackgroundColor(Color.YELLOW);
    }

    private void setTextViewBad(TextView textview) {
        textview.setBackgroundColor(Color.RED);
    }



}

