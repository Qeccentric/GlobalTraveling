package com.kankan.globaltraveling;

import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.telephony.CellInfo;
import android.telephony.CellLocation;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.bluetooth.BluetoothAdapter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookMain implements IXposedHookLoadPackage {

    private static final String FILE_PATH = "/data/local/tmp/irest_loc.conf";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals("com.kankan.globaltraveling")) return;

        // 1. 系统底层 Location 劫持 (基石)
        XC_MethodHook universalLocHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                applyLocationFix((Location) param.thisObject);
            }
        };
        XposedHelpers.findAndHookConstructor(Location.class, String.class, universalLocHook);
        XposedHelpers.findAndHookMethod(Location.class, "set", Location.class, universalLocHook);

        XC_MethodHook getterHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                double[] c = readFromTmp();
                if (c == null) return;
                double d = getDrift();
                String name = param.method.getName();
                if (name.equals("getLatitude")) param.setResult(c[0] + d);
                else if (name.equals("getLongitude")) param.setResult(c[1] + d);
                else if (name.equals("getAccuracy")) param.setResult(3.0f);
                else if (name.equals("getSpeed")) param.setResult(0.0f);
                else if (name.equals("getAltitude")) param.setResult(50.0d);
            }
        };
        XposedHelpers.findAndHookMethod(Location.class, "getLatitude", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "getLongitude", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "getAccuracy", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "getSpeed", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "getAltitude", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "isFromMockProvider", XC_MethodReplacement.returnConstant(false));

        // 2. 解决“定位中”：劫持 LastKnownLocation (关键)
        XposedBridge.hookAllMethods(LocationManager.class, "getLastKnownLocation", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Location loc = (Location) param.getResult();
                if (loc == null && readFromTmp() != null) {
                    loc = new Location(LocationManager.GPS_PROVIDER);
                    param.setResult(loc);
                }
                if (loc != null) applyLocationFix(loc);
            }
        });

        // 3. 监听器劫持 (防跳回)
        XposedBridge.hookAllMethods(LocationManager.class, "requestLocationUpdates", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                for (Object arg : param.args) {
                    if (arg != null && arg.getClass().getName().contains("LocationListener")) {
                        XposedHelpers.findAndHookMethod(arg.getClass(), "onLocationChanged", Location.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                                applyLocationFix((Location) p.args[0]);
                            }
                        });
                        // 瞬时注入
                        double[] c = readFromTmp();
                        if (c != null) {
                            Location fastLoc = new Location(LocationManager.GPS_PROVIDER);
                            applyLocationFix(fastLoc);
                            try { ((LocationListener)arg).onLocationChanged(fastLoc); } catch (Throwable t) {}
                        }
                    }
                }
            }
        });

        // 4. 虚拟飞行模式 (基站/SIM/蓝牙 彻底封杀)
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getSimState", XC_MethodReplacement.returnConstant(TelephonyManager.SIM_STATE_ABSENT));
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getAllCellInfo", XC_MethodReplacement.returnConstant(new ArrayList<CellInfo>()));
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getCellLocation", XC_MethodReplacement.returnConstant(null));
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "requestCellInfoUpdate",
                        Executor.class, TelephonyManager.CellInfoCallback.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (readFromTmp() != null) {
                                    Executor ex = (Executor) param.args[0];
                                    TelephonyManager.CellInfoCallback cb = (TelephonyManager.CellInfoCallback) param.args[1];
                                    if (ex != null && cb != null) ex.execute(() -> cb.onCellInfo(new ArrayList<>()));
                                    param.setResult(null);
                                }
                            }
                        });
            } catch (Throwable t) {}
        }
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "listen", PhoneStateListener.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (readFromTmp() != null) param.args[1] = PhoneStateListener.LISTEN_NONE;
            }
        });

        try {
            XposedHelpers.findAndHookMethod(BluetoothAdapter.class, "getBondedDevices", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (readFromTmp() != null) param.setResult(Collections.emptySet());
                }
            });
            XposedHelpers.findAndHookMethod(BluetoothAdapter.class, "startDiscovery", XC_MethodReplacement.returnConstant(false));
        } catch (Throwable t) {}

        // 5. Wi-Fi 伪造 (Mandba-WiFi)
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getNetworkType", XC_MethodReplacement.returnConstant(TelephonyManager.NETWORK_TYPE_UNKNOWN));
        try {
            XposedHelpers.findAndHookMethod(ConnectivityManager.class, "getActiveNetworkInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (readFromTmp() != null) {
                        NetworkInfo info = (NetworkInfo) param.getResult();
                        if (info != null) {
                            XposedHelpers.setIntField(info, "mNetworkType", ConnectivityManager.TYPE_WIFI);
                            XposedHelpers.setObjectField(info, "mTypeName", "WIFI");
                            XposedHelpers.setObjectField(info, "mState", NetworkInfo.State.CONNECTED);
                        }
                    }
                }
            });
        } catch (Throwable t) {}

        XposedHelpers.findAndHookMethod(WifiManager.class, "getConnectionInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (readFromTmp() != null) {
                    WifiInfo info = (WifiInfo) param.getResult();
                    if (info != null) {
                        XposedHelpers.setObjectField(info, "mBSSID", "00:11:22:33:44:55");
                        XposedHelpers.setObjectField(info, "mSSID", "\"Mandba-WiFi\"");
                        XposedHelpers.setObjectField(info, "mMacAddress", "02:00:00:00:00:00");
                        XposedHelpers.setObjectField(info, "mRssi", -45);
                    }
                }
            }
        });
        XposedHelpers.findAndHookMethod(WifiManager.class, "getScanResults", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (readFromTmp() != null) {
                    List<ScanResult> list = new ArrayList<>();
                    try {
                        Constructor<ScanResult> ctor = ScanResult.class.getDeclaredConstructor();
                        ctor.setAccessible(true);
                        ScanResult w = ctor.newInstance();
                        w.SSID = "Mandba-WiFi";
                        w.BSSID = "00:11:22:33:44:55";
                        w.level = -45;
                        w.capabilities = "[WPA2-PSK-CCMP][ESS]";
                        w.frequency = 2412;
                        if (Build.VERSION.SDK_INT >= 17) w.timestamp = SystemClock.elapsedRealtime() * 1000;
                        list.add(w);
                    } catch (Exception ignored) {}
                    param.setResult(list);
                }
            }
        });

        // 6. 辅助伪造
        XposedBridge.hookAllMethods(LocationManager.class, "addNmeaListener", XC_MethodReplacement.returnConstant(true));
        if (Build.VERSION.SDK_INT >= 24) {
            XposedHelpers.findAndHookMethod(GnssStatus.class, "getSatelliteCount", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (readFromTmp() != null) param.setResult(15);
                }
            });
        }

        // ==========================================
        // 7. SDK 专项 Hook (仅保留 高德 和 腾讯)
        // ==========================================

        // 腾讯 (必须有，否则 QQ/JD 会跳)
        try {
            Class<?> tencent = XposedHelpers.findClass("com.tencent.map.geolocation.TencentLocation", lpparam.classLoader);
            hookSDK(tencent, getterHook);
            XposedHelpers.findAndHookMethod(tencent, "getProvider", XC_MethodReplacement.returnConstant("gps"));
        } catch (Throwable t) {}

        // 高德 (必须有，否则高德会跳)
        try {
            Class<?> amap = XposedHelpers.findClass("com.amap.api.location.AMapLocation", lpparam.classLoader);
            hookSDK(amap, getterHook);
            XposedHelpers.findAndHookMethod(amap, "getLocationType", XC_MethodReplacement.returnConstant(1));
        } catch (Throwable t) {}

        // 【注意】这里故意没有 Hook 百度 (BDLocation)，让它回退到系统层
    }

    private void hookSDK(Class<?> clazz, XC_MethodHook hook) {
        try { XposedHelpers.findAndHookMethod(clazz, "getLatitude", hook); } catch (Throwable t) {}
        try { XposedHelpers.findAndHookMethod(clazz, "getLongitude", hook); } catch (Throwable t) {}
        try { XposedHelpers.findAndHookMethod(clazz, "getAccuracy", hook); } catch (Throwable t) {}
    }

    private void applyLocationFix(Location loc) {
        if (loc == null) return;
        double[] c = readFromTmp();
        if (c != null) {
            double d = getDrift();
            try {
                XposedHelpers.setDoubleField(loc, "mLatitude", c[0] + d);
                XposedHelpers.setDoubleField(loc, "mLongitude", c[1] + d);
            } catch (Throwable t) {
                loc.setLatitude(c[0] + d);
                loc.setLongitude(c[1] + d);
            }
            loc.setAccuracy(3.0f);
            loc.setProvider(LocationManager.GPS_PROVIDER);
            loc.setSpeed(0.0f);
            loc.setAltitude(50.0d);
            loc.setTime(System.currentTimeMillis());
            if (Build.VERSION.SDK_INT >= 17) {
                loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            }
            try { XposedHelpers.setBooleanField(loc, "mIsFromMockProvider", false); } catch (Throwable ignored) {}
        }
    }

    private double getDrift() { return (new Random().nextDouble() - 0.5) * 0.00002; }

    private double[] readFromTmp() {
        try {
            File file = new File(FILE_PATH);
            if (!file.exists() || !file.canRead()) return null;
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line = br.readLine();
            br.close();
            if (line == null) return null;
            String[] p = line.split(",");
            if (p.length < 3 || !"1".equals(p[2])) return null;
            return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])};
        } catch (Exception e) { return null; }
    }
}
