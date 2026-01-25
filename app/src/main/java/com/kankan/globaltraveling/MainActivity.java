package com.kankan.globaltraveling;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.services.core.ServiceSettings;
import com.amap.api.services.help.Inputtips;
import com.amap.api.services.help.InputtipsQuery;
import com.amap.api.services.help.Tip;

import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity implements Inputtips.InputtipsListener {

    private MapView mapView;
    private AMap aMap;
    private TextView tvStatus;
    private AutoCompleteTextView etSearch;
    private LinearLayout historyContainer;

    private static final String FILE_PATH = "/data/local/tmp/irest_loc.conf";
    private static final String PREF_NAME = "IdentityConfig";
    private static final String PREF_HISTORY = "LocHistory";
    private static final String KEY_HISTORY = "history_list";
    private static final String KEY_DEF_LAT = "def_lat";
    private static final String KEY_DEF_LNG = "def_lng";
    private static final String KEY_DEF_NAME = "def_name";

    // 免责声明 Key
    private static final String PREF_APP = "AppConfig";
    private static final String KEY_AGREED = "is_agreed_disclaimer";

    private String fixedMac;
    private String fixedSSID;
    private double selectLat = 0;
    private double selectLng = 0;
    private String currentName = "手动选点";

    // 防抖
    private Handler searchHandler = new Handler();
    private Runnable searchRunnable;

    // 保存 SavedInstanceState 供延迟初始化使用
    private Bundle savedState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 1. 高德合规 (必须最先执行)
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        try {
            ServiceSettings.updatePrivacyShow(this, true, true);
            ServiceSettings.updatePrivacyAgree(this, true);
        } catch (Throwable t) {}

        super.onCreate(savedInstanceState);

        // --- 核心修改：完全透明 + 黑色图标 ---

        // 1. 把背景色设为【完全透明】，去掉任何白色/浅蓝色的遮挡
        getWindow().setStatusBarColor(Color.TRANSPARENT);

        // 2. 组合标志位：
        // LAYOUT_FULLSCREEN: 让地图顶上去
        // LIGHT_STATUS_BAR:  让图标变黑 (因为地图通常是浅色的)
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        );

        setContentView(R.layout.activity_main);

        setContentView(R.layout.activity_main);
        // 3. 检查免责声明
        if (checkDisclaimer()) {
            initApp();
        }
    }

    /**
     * 检查并显示免责声明
     */
    private boolean checkDisclaimer() {
        SharedPreferences prefs = getSharedPreferences(PREF_APP, Context.MODE_PRIVATE);
        boolean isAgreed = prefs.getBoolean(KEY_AGREED, false);

        if (isAgreed) {
            return true; // 已同意，继续
        }

        // 显示弹窗
        new AlertDialog.Builder(this)
                .setTitle("使用须知")
                .setMessage("欢迎使用 Shadow！\n\n" +
                        "1. 本软件仅供技术研究与学习使用，禁止用于任何非法用途（如考勤作弊、游戏外挂等）。\n" +
                        "2. 使用本软件产生的任何后果由用户自行承担，开发者不承担任何责任。\n" +
                        "3. 本软件需要 Root 权限才能运行，请确保您了解 Root 的风险。\n\n" +
                        "点击“同意并继续”即表示您已阅读并接受上述条款。")
                .setCancelable(false)
                .setPositiveButton("同意并继续", (dialog, which) -> {
                    prefs.edit().putBoolean(KEY_AGREED, true).apply();
                    initApp(); // 用户点击同意后，才初始化 App
                })
                .setNegativeButton("拒绝并退出", (dialog, which) -> {
                    finish();
                    System.exit(0);
                })
                .show();

        return false; // 暂停初始化
    }

    /**
     * 核心初始化逻辑 (布局加载、地图初始化)
     */
    private void initApp() {
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        etSearch = findViewById(R.id.et_search);
        historyContainer = findViewById(R.id.history_container);

        mapView = findViewById(R.id.map);
        // 使用保存的 Bundle 初始化地图
        mapView.onCreate(savedState);
        if (aMap == null) aMap = mapView.getMap();

        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(39.9042, 116.4074), 10));

        initIdentity();
        loadHistory();
        loadDefaultLocation();

        aMap.setOnMapLongClickListener(latLng -> updateSelection(latLng.latitude, latLng.longitude, "手动选点"));

        // 搜索逻辑 (带防抖)
        etSearch.setThreshold(1);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchRunnable != null) searchHandler.removeCallbacks(searchRunnable);
            }
            @Override public void afterTextChanged(Editable s) {
                searchRunnable = () -> {
                    String newText = s.toString().trim();
                    if (newText.length() > 0) {
                        InputtipsQuery inputquery = new InputtipsQuery(newText, "");
                        inputquery.setCityLimit(false);
                        Inputtips inputTips = new Inputtips(MainActivity.this, inputquery);
                        inputTips.setInputtipsListener(MainActivity.this);
                        inputTips.requestInputtipsAsyn();
                    }
                };
                searchHandler.postDelayed(searchRunnable, 600);
            }
        });

        etSearch.setOnItemClickListener((parent, view, position, id) -> {
            Tip tip = (Tip) parent.getItemAtPosition(position);
            if (tip.getPoint() != null) {
                double lat = tip.getPoint().getLatitude();
                double lon = tip.getPoint().getLongitude();
                aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lon), 16));
                updateSelection(lat, lon, tip.getName());
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
                etSearch.setText("");
                etSearch.clearFocus();
            }
        });

        // 按钮事件
        findViewById(R.id.btn_start).setOnClickListener(v -> {
            if (selectLat == 0) return;
            writeToSystemTmp(selectLat + "," + selectLng + ",1," + fixedMac + "," + fixedSSID, true);
            saveHistory(currentName, selectLat, selectLng);
        });

        findViewById(R.id.btn_stop).setOnClickListener(v -> writeToSystemTmp("0,0,0,0,0", false));

        findViewById(R.id.btn_set_default).setOnClickListener(v -> {
            if (selectLat == 0) {
                Toast.makeText(this, "请先选点", Toast.LENGTH_SHORT).show();
                return;
            }
            getSharedPreferences(PREF_HISTORY, Context.MODE_PRIVATE).edit()
                    .putFloat(KEY_DEF_LAT, (float) selectLat)
                    .putFloat(KEY_DEF_LNG, (float) selectLng)
                    .putString(KEY_DEF_NAME, currentName)
                    .apply();
            Toast.makeText(this, "✅ 已设为默认位置", Toast.LENGTH_SHORT).show();
        });
    }

    // --- 辅助逻辑保持不变 ---

    private void loadDefaultLocation() {
        SharedPreferences prefs = getSharedPreferences(PREF_HISTORY, Context.MODE_PRIVATE);
        float lat = prefs.getFloat(KEY_DEF_LAT, 0);
        float lng = prefs.getFloat(KEY_DEF_LNG, 0);
        String name = prefs.getString(KEY_DEF_NAME, "默认位置");
        if (lat != 0 && lng != 0) {
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lng), 16));
            updateSelection(lat, lng, name);
        } else {
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(39.9042, 116.4074), 10));
        }
    }

    private void loadHistory() {
        historyContainer.removeAllViews();
        SharedPreferences prefs = getSharedPreferences(PREF_HISTORY, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_HISTORY, "");
        if (raw.isEmpty()) return;
        String[] list = raw.split(",");
        for (String item : list) {
            String[] p = item.split("\\|");
            if (p.length < 3) continue;
            addHistoryChip(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]));
        }
    }

    private void saveHistory(String name, double lat, double lng) {
        if (name.equals("手动选点")) return;
        SharedPreferences prefs = getSharedPreferences(PREF_HISTORY, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_HISTORY, "");
        String newItem = name + "|" + lat + "|" + lng;
        List<String> list = new ArrayList<>();
        if (!raw.isEmpty()) { for (String s : raw.split(",")) list.add(s); }
        for (int i = 0; i < list.size(); i++) { if (list.get(i).startsWith(name + "|")) { list.remove(i); break; } }
        list.add(0, newItem);
        if (list.size() > 8) list = list.subList(0, 8);
        StringBuilder sb = new StringBuilder();
        for (String s : list) { if (sb.length() > 0) sb.append(","); sb.append(s); }
        prefs.edit().putString(KEY_HISTORY, sb.toString()).apply();
        loadHistory();
    }

    private void deleteHistory(String name) {
        SharedPreferences prefs = getSharedPreferences(PREF_HISTORY, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_HISTORY, "");
        List<String> list = new ArrayList<>();
        for (String s : raw.split(",")) { if (!s.startsWith(name + "|")) list.add(s); }
        StringBuilder sb = new StringBuilder();
        for (String s : list) { if (sb.length() > 0) sb.append(","); sb.append(s); }
        prefs.edit().putString(KEY_HISTORY, sb.toString()).apply();
        loadHistory();
    }

    private void addHistoryChip(String name, double lat, double lng) {
        CardView card = new CardView(this);
        card.setCardBackgroundColor(Color.parseColor("#F0F7FF"));
        card.setRadius(dp2px(20));
        card.setCardElevation(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp2px(4), dp2px(12), dp2px(4));
        card.setLayoutParams(params);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(dp2px(16), dp2px(8), dp2px(12), dp2px(8));
        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextColor(Color.parseColor("#333333"));
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setOnClickListener(v -> {
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lng), 16));
            updateSelection(lat, lng, name);
        });
        TextView btnDel = new TextView(this);
        btnDel.setText("✕");
        btnDel.setTextColor(Color.parseColor("#AABBCF"));
        btnDel.setTextSize(12);
        btnDel.setPadding(dp2px(10), 0, 0, 0);
        btnDel.setOnClickListener(v -> deleteHistory(name));
        layout.addView(tv);
        layout.addView(btnDel);
        card.addView(layout);
        historyContainer.addView(card);
    }

    @Override
    public void onGetInputtips(List<Tip> tipList, int rCode) {
        if (rCode == 1000 && tipList != null) {
            ArrayAdapter<Tip> adapter = new ArrayAdapter<Tip>(this, android.R.layout.simple_list_item_1, tipList) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    View view = super.getView(position, convertView, parent);
                    TextView text = (TextView) view.findViewById(android.R.id.text1);
                    text.setText(getItem(position).getName());
                    return view;
                }
            };
            etSearch.setAdapter(adapter);
            adapter.notifyDataSetChanged();
            runOnUiThread(() -> {
                if (etSearch.hasFocus() && etSearch.getText().length() > 0) etSearch.showDropDown();
            });
        }
    }

    private void updateSelection(double lat, double lng, String title) {
        selectLat = lat;
        selectLng = lng;
        currentName = title;
        aMap.clear();
        aMap.addMarker(new MarkerOptions().position(new LatLng(lat, lng)).title(title));
        tvStatus.setText("已选: " + title);
    }

    private void initIdentity() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        fixedMac = prefs.getString("mac", "");
        fixedSSID = prefs.getString("ssid", "");
        if (fixedMac.isEmpty()) {
            fixedMac = generateRandomMac();
            fixedSSID = generateRandomSSID();
            prefs.edit().putString("mac", fixedMac).putString("ssid", fixedSSID).apply();
        }
    }

    private String generateRandomMac() {
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        sb.append("ac");
        for (int i = 0; i < 5; i++) {
            sb.append(":");
            String s = Integer.toHexString(r.nextInt(256));
            if (s.length() < 2) sb.append("0");
            sb.append(s);
        }
        return sb.toString().toLowerCase();
    }

    private String generateRandomSSID() {
        String[] brands = {"TP-LINK", "Xiaomi", "HUAWEI", "Office-Guest"};
        return brands[new Random().nextInt(brands.length)] + "_" + (1000 + new Random().nextInt(8999));
    }

    private void writeToSystemTmp(String content, boolean isStart) {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(p.getOutputStream());
                os.writeBytes("echo \"" + content + "\" > " + FILE_PATH + "\n");
                os.writeBytes("chmod 666 " + FILE_PATH + "\n");
                os.writeBytes("chcon u:object_r:shell_data_file:s0 " + FILE_PATH + "\n");
                os.writeBytes("exit\n");
                os.flush();
                int ret = p.waitFor();
                runOnUiThread(() -> {
                    if (ret == 0) {
                        String msg = isStart ? "模拟已启动" : "模拟已结束";
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Root授权失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    private int dp2px(float dp) {
        float scale = getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }

    @Override protected void onDestroy() { super.onDestroy(); if(mapView!=null) mapView.onDestroy(); }
    @Override protected void onResume() { super.onResume(); if(mapView!=null) mapView.onResume(); }
    @Override protected void onPause() { super.onPause(); if(mapView!=null) mapView.onPause(); }
    @Override protected void onSaveInstanceState(Bundle outState) { super.onSaveInstanceState(outState); if(mapView!=null) mapView.onSaveInstanceState(outState); }
}
