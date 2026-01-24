package com.kankan.globaltraveling;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.services.help.Inputtips;
import com.amap.api.services.help.InputtipsQuery;
import com.amap.api.services.help.Tip;

import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements Inputtips.InputtipsListener {

    private MapView mapView;
    private AMap aMap;
    private TextView tvStatus;
    private AutoCompleteTextView etSearch; // 改用自动补全框

    private static final String FILE_PATH = "/data/local/tmp/irest_loc.conf";
    private double selectLat = 0, selectLng = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        com.amap.api.services.core.ServiceSettings.updatePrivacyShow(this, true, true);
        com.amap.api.services.core.ServiceSettings.updatePrivacyAgree(this, true);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        // 初始化 AutoCompleteTextView
        etSearch = findViewById(R.id.et_search);

        mapView = findViewById(R.id.map);
        mapView.onCreate(savedInstanceState);
        if (aMap == null) aMap = mapView.getMap();

        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(39.9042, 116.4074), 10));

        aMap.setOnMapLongClickListener(latLng -> updateSelection(latLng.latitude, latLng.longitude, "手动选点"));

        findViewById(R.id.btn_start).setOnClickListener(v -> {
            if (selectLat == 0) return;
            writeToSystemTmp(selectLat + "," + selectLng + ",1");
        });

        findViewById(R.id.btn_stop).setOnClickListener(v -> writeToSystemTmp("0,0,0"));

        // --- 核心：配置搜索联想 ---
        etSearch.setThreshold(1); // 输入1个字就开始联想

        // 监听输入
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String newText = s.toString().trim();
                if (newText.length() > 0) {
                    // 发起输入提示请求
                    InputtipsQuery inputquery = new InputtipsQuery(newText, "");
                    Inputtips inputTips = new Inputtips(MainActivity.this, inputquery);
                    inputTips.setInputtipsListener(MainActivity.this);
                    inputTips.requestInputtipsAsyn();
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // 监听下拉列表点击
        etSearch.setOnItemClickListener((parent, view, position, id) -> {
            // 获取选中的 Tip 对象
            Tip tip = (Tip) parent.getItemAtPosition(position);
            if (tip.getPoint() != null) {
                double lat = tip.getPoint().getLatitude();
                double lon = tip.getPoint().getLongitude();
                // 移动地图并选点
                aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lon), 16));
                updateSelection(lat, lon, tip.getName());
                // 隐藏键盘
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
            } else {
                Toast.makeText(MainActivity.this, "该地点没有坐标信息", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // --- 联想结果回调 ---
    @Override
    public void onGetInputtips(List<Tip> tipList, int rCode) {
        if (rCode == 1000 && tipList != null) {
            // 使用 ArrayAdapter 显示结果
            // Tip 重写了 toString()，默认显示名字，如果你想自定义显示格式，需要自定义 Adapter
            ArrayAdapter<Tip> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, tipList);
            etSearch.setAdapter(adapter);
            adapter.notifyDataSetChanged();
        }
    }

    private void updateSelection(double lat, double lng, String title) {
        selectLat = lat;
        selectLng = lng;
        aMap.clear();
        aMap.addMarker(new MarkerOptions().position(new LatLng(lat, lng)).title(title));
        tvStatus.setText("已选: " + title + "\n" + String.format("%.5f, %.5f", lat, lng));
    }

    private void writeToSystemTmp(String content) {
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
                    if (ret == 0) Toast.makeText(this, "🚀 坐标已锁定", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    @Override protected void onDestroy() { super.onDestroy(); mapView.onDestroy(); }
    @Override protected void onResume() { super.onResume(); mapView.onResume(); }
    @Override protected void onPause() { super.onPause(); mapView.onPause(); }
    @Override protected void onSaveInstanceState(Bundle outState) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState); }
}
