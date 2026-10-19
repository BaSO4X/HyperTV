package io.github.z1812.hypertv;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;

/**
 * HyperTV 模块的电视端设置页（MD3、遥控器可操作）。
 *
 * <p>两块内容：模块激活状态卡（实时读取 LSPosed service）+ 功能开关卡。
 * 开关通过 RemotePreferences 直接写进 LSPosed，Hook 进程收到变更后立即按新配置工作，
 * 不需要重启桌面。
 */
public final class MainActivity extends AppCompatActivity implements PrefsStore.Observer {

    private PrefsStore store;

    private TextView statusValue;
    private TextView statusDetail;
    private TextView statusBadge;
    private ImageView statusIcon;

    private MaterialSwitch adsSwitch;
    private MaterialSwitch elderSwitch;
    private MaterialSwitch previewSwitch;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = PrefsStore.get(this);

        statusValue = findViewById(R.id.tv_status_value);
        statusDetail = findViewById(R.id.tv_status_detail);
        statusBadge = findViewById(R.id.tv_status_badge);
        statusIcon = findViewById(R.id.iv_status_icon);
        adsSwitch = findViewById(R.id.sw_ads);
        elderSwitch = findViewById(R.id.sw_elder);
        previewSwitch = findViewById(R.id.sw_preview);

        // 输入源预览常显：hook 里每次回调都读配置，切换后返回桌面即可看到效果
        MaterialCardView previewCard = findViewById(R.id.card_preview);
        bindCard(previewCard, previewSwitch, Constants.KEY_PREVIEW_ALWAYS_SHOW,
                R.string.hint_take_effect_now);

        MaterialCardView adsCard = findViewById(R.id.card_ads);
        bindCard(adsCard, adsSwitch, Constants.KEY_REMOVE_MY_APP_ADS, R.string.hint_reenter_page);

        MaterialCardView elderCard = findViewById(R.id.card_elder);
        bindCard(elderCard, elderSwitch, Constants.KEY_REMOVE_ELDER_RECOMMEND,
                R.string.hint_take_effect_now);

        refreshSwitch();
        // 进入页面直接把焦点交给第一个功能卡，遥控器上下键即可移动
        previewCard.post(previewCard::requestFocus);
    }

    /** 整张卡片即遥控器焦点目标，OK 键切换对应开关。 */
    private void bindCard(MaterialCardView card, MaterialSwitch sw, String key, int hintRes) {
        card.setOnClickListener(v -> toggleSwitch(sw, key, hintRes));
        card.setOnFocusChangeListener((v, hasFocus) -> v.setSelected(hasFocus));
    }

    @Override
    protected void onStart() {
        super.onStart();
        store.addObserver(this);
        refreshStatus();
        refreshSwitch();
    }

    @Override
    protected void onStop() {
        store.removeObserver(this);
        super.onStop();
    }

    @Override
    public void onServiceStateChanged() {
        runOnUiThread(() -> {
            refreshStatus();
            refreshSwitch();
        });
    }

    // ── 功能开关 ────────────────────────────────────────────────────────────────

    private void toggleSwitch(MaterialSwitch sw, String key, int hintRes) {
        boolean next = !sw.isChecked();
        boolean synced = store.setBoolean(key, next);
        sw.setChecked(next);
        Toast.makeText(this, synced ? hintRes : R.string.hint_saved_locally,
                Toast.LENGTH_SHORT).show();
    }

    private void refreshSwitch() {
        adsSwitch.setChecked(store.getBoolean(
                Constants.KEY_REMOVE_MY_APP_ADS, Constants.DEFAULT_REMOVE_MY_APP_ADS));
        elderSwitch.setChecked(store.getBoolean(
                Constants.KEY_REMOVE_ELDER_RECOMMEND, Constants.DEFAULT_REMOVE_ELDER_RECOMMEND));
        previewSwitch.setChecked(store.getBoolean(
                Constants.KEY_PREVIEW_ALWAYS_SHOW, Constants.DEFAULT_PREVIEW_ALWAYS_SHOW));
    }

    // ── 激活状态 ────────────────────────────────────────────────────────────────

    private void refreshStatus() {
        if (!store.isServiceConnected()) {
            applyState(R.string.status_no_service,
                    getString(R.string.status_no_service_detail),
                    R.color.state_error, false);
            return;
        }

        int api = store.apiVersion();
        if (api < Constants.MIN_API_VERSION) {
            applyState(R.string.status_api_low,
                    getString(R.string.status_api_low_detail, api),
                    R.color.state_warn, false);
            return;
        }

        List<String> scope = store.scope();
        if (!scope.contains(Constants.TARGET_PACKAGE)) {
            applyState(R.string.status_scope_missing,
                    getString(R.string.status_scope_missing_detail, Constants.TARGET_PACKAGE),
                    R.color.state_warn, false);
            return;
        }

        String framework = store.frameworkName();
        if (framework.isEmpty()) {
            framework = getString(R.string.status_unknown_framework);
        }
        String version = store.frameworkVersion();
        applyState(R.string.status_active,
                getString(R.string.status_active_detail, framework, version, api,
                        Constants.TARGET_PACKAGE),
                R.color.state_ok, true);
    }

    private void applyState(int titleRes, String detail, int colorRes, boolean active) {
        int color = ContextCompat.getColor(this, colorRes);

        statusValue.setText(titleRes);
        statusValue.setTextColor(color);
        statusDetail.setText(detail);

        ImageViewCompat.setImageTintList(statusIcon, ColorStateList.valueOf(color));

        statusBadge.setText(active ? R.string.badge_active : R.string.badge_inactive);
        statusBadge.setTextColor(color);
        statusBadge.setBackground(pill(color));
    }

    private GradientDrawable pill(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(999));
        drawable.setColor(ColorUtils.setAlphaComponent(color, 40));
        return drawable;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
