package com.carlink.headunit;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.carlink.headunit.net.Protocol;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Connection screen: phone IP / control port inputs, connect button, status line.
 * The last entered values are persisted in SharedPreferences.
 * <p>
 * On a phone hotspot the phone IS the WiFi gateway, so on first launch the gateway
 * address is prefilled and the connection is started automatically (once per process);
 * only when that fails (or no gateway was found) does the user type the IP by hand. A
 * saved address that differs from both the factory default and the current gateway is
 * treated as a deliberate manual choice and left untouched.
 */
public class MainActivity extends Activity {

    private static final String TAG = "CarLinkHeadunit";
    static final String EXTRA_IP = "com.carlink.headunit.extra.IP";
    static final String EXTRA_PORT = "com.carlink.headunit.extra.PORT";
    static final String RESULT_EXTRA_DISCONNECT_REASON = "com.carlink.headunit.extra.DISCONNECT_REASON";

    private static final int REQUEST_PROJECTION = 1;

    private static final String PREFS_NAME = "carlink";
    private static final String PREF_IP = "last_ip";
    private static final String PREF_PORT = "last_port";

    /** Phone hotspot default gateway address. */
    private static final String DEFAULT_IP = "192.168.43.1";

    /** Process-wide one-shot: auto-connect fires only from the first onCreate of the
     * process. Returning from a failed projection (onActivityResult) or a configuration
     * change must never re-trigger it — that would loop and take the manual-input stage
     * away from the user. */
    private static boolean autoConnectAttempted;

    private SharedPreferences prefs;
    private EditText editIp;
    private EditText editPort;
    private TextView textStatus;
    private Button btnConnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        editIp = findViewById(R.id.edit_ip);
        editPort = findViewById(R.id.edit_port);
        textStatus = findViewById(R.id.text_status);
        btnConnect = findViewById(R.id.btn_connect);

        editIp.setText(prefs.getString(PREF_IP, DEFAULT_IP));
        editPort.setText(String.valueOf(prefs.getInt(PREF_PORT, Protocol.DEFAULT_CONTROL_PORT)));

        btnConnect.setOnClickListener(v -> startProjection());
        // IME "done" on the port field connects directly, without reaching for the button
        editPort.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                startProjection();
                return true;
            }
            return false;
        });

        tryAutoConnect();
    }

    /**
     * One-shot auto-connect: on a phone hotspot the phone is the WiFi gateway, so prefill
     * the gateway address and connect right away. Does nothing when no gateway can be
     * determined (no WiFi / no default route) or when the saved address is a deliberate
     * manual choice — the manual flow stays as is.
     */
    private void tryAutoConnect() {
        if (autoConnectAttempted) {
            return;
        }
        autoConnectAttempted = true;
        String gateway = findGatewayIp();
        if (gateway == null) {
            Log.i(TAG, "no WiFi gateway found, staying on manual input");
            return;
        }
        // Respect an explicit earlier choice: a saved address that is neither the factory
        // default nor the current gateway was typed by hand (and may well be the only one
        // that works, e.g. the phone's service listens on another interface). Overriding it
        // with the gateway on every cold start would force the user to cancel a doomed
        // auto-connect each time before re-entering their address.
        String saved = editIp.getText().toString().trim();
        if (!saved.equals(gateway) && !saved.equals(DEFAULT_IP)) {
            Log.i(TAG, "saved address " + saved + " differs from gateway " + gateway
                    + ", keeping the manual choice");
            return;
        }
        Log.i(TAG, "WiFi gateway is " + gateway + ", auto-connecting");
        editIp.setText(gateway);
        startProjection();
        if (!btnConnect.isEnabled()) {
            // startProjection() launched (it only refuses on invalid input, excluded by
            // the gateway validation); replace its generic status with the auto-connect one
            textStatus.setText(getString(R.string.status_auto_connecting, gateway));
        }
    }

    /**
     * IPv4 default-gateway address of the current WiFi connection, or null if there is
     * none (no WiFi, no default route, or only an IPv6 gateway).
     * <p>
     * All networks are scanned for a WiFi transport instead of only consulting
     * getActiveNetwork(): on a head unit with Ethernet up (or with mobile data preferred)
     * the active network is not WiFi even when the hotspot connection is fine, and a VPN
     * network never carries the WiFi transport either. Reading the WiFi network's own
     * routes also means the Ethernet gateway can never be picked by mistake.
     */
    @SuppressWarnings("deprecation") // WifiManager.getDhcpInfo(): fallback for the gateway
    private String findGatewayIp() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean wifiPresent = false;
        if (cm != null) {
            Network[] networks = cm.getAllNetworks();
            if (networks != null) {
                for (Network network : networks) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        continue;
                    }
                    wifiPresent = true;
                    LinkProperties lp = cm.getLinkProperties(network);
                    if (lp == null) {
                        continue;
                    }
                    for (RouteInfo route : lp.getRoutes()) {
                        if (route.isDefaultRoute()) {
                            String ip = ipv4Address(route.getGateway());
                            if (ip != null) {
                                return ip;
                            }
                        }
                    }
                }
            }
        }
        // Fallback: the gateway from the last successful DHCP request (deprecated since
        // API 31 but functional from minSdk 24; the int is little-endian, 0 = none). Only
        // trusted while a WiFi network is actually present — with WiFi down the value can
        // be a leftover from an earlier network and would aim the auto-connect at an
        // address that was never the phone.
        if (wifiPresent) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                DhcpInfo dhcp = wm.getDhcpInfo();
                if (dhcp != null && dhcp.gateway != 0) {
                    int gw = dhcp.gateway;
                    return (gw & 0xff) + "." + ((gw >> 8) & 0xff) + "."
                            + ((gw >> 16) & 0xff) + "." + ((gw >>> 24) & 0xff);
                }
            }
        }
        return null;
    }

    /** Dotted-quad string for an IPv4 address, excluding the unusable 0.0.0.0. */
    private static String ipv4Address(InetAddress address) {
        if (address instanceof Inet4Address) {
            String ip = address.getHostAddress();
            if (ip != null && !"0.0.0.0".equals(ip) && isValidIpv4(ip)) {
                return ip;
            }
        }
        return null;
    }

    private void startProjection() {
        // The button stays disabled until the projection returns; check it here (not only via
        // the disabled click target) because the IME action listener below fires regardless of
        // the button state and would otherwise stack a second ProjectionActivity racing for
        // the phone's single session slot
        if (!btnConnect.isEnabled()) {
            return;
        }
        String ip = editIp.getText().toString().trim();
        if (!isValidIpv4(ip)) {
            Log.d(TAG, "invalid IP input: \"" + ip + "\"");
            textStatus.setText(R.string.error_invalid_ip);
            return;
        }
        final int port;
        try {
            port = Integer.parseInt(editPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            Log.d(TAG, "invalid port input: \"" + editPort.getText().toString().trim() + "\"");
            textStatus.setText(R.string.error_invalid_port);
            return;
        }
        if (port <= 0 || port > 65535) {
            Log.d(TAG, "port out of range: " + port);
            textStatus.setText(R.string.error_invalid_port);
            return;
        }

        prefs.edit().putString(PREF_IP, ip).putInt(PREF_PORT, port).apply();

        // Disable until the projection returns: a double tap must not stack two
        // ProjectionActivity instances racing for the phone's single session slot
        btnConnect.setEnabled(false);
        textStatus.setText(R.string.status_connecting);
        Log.i(TAG, "connecting to " + ip + ":" + port);
        Intent intent = new Intent(this, ProjectionActivity.class);
        intent.putExtra(EXTRA_IP, ip);
        intent.putExtra(EXTRA_PORT, port);
        startActivityForResult(intent, REQUEST_PROJECTION);
    }

    /** Basic dotted-quad validation: four numeric octets, each within 0-255. */
    private static boolean isValidIpv4(String ip) {
        String[] octets = ip.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            for (int i = 0; i < octet.length(); i++) {
                char c = octet.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PROJECTION) {
            btnConnect.setEnabled(true);
            // Park the focus on the button instead of a text field: the most likely next
            // action after a return is reconnecting (one confirm-key press), and keeping
            // focus off the EditTexts keeps the soft keyboard closed (stateHidden)
            btnConnect.requestFocus();
            String reason = data != null ? data.getStringExtra(RESULT_EXTRA_DISCONNECT_REASON) : null;
            if (reason != null && !reason.isEmpty()) {
                Log.i(TAG, "projection ended, reason: " + reason);
                textStatus.setText(getString(R.string.status_disconnected_with_reason, reason));
            } else {
                Log.i(TAG, "projection ended by the user");
                textStatus.setText(R.string.status_disconnected);
            }
        }
    }
}
