package com.xiaomei.assistant.uihost;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.xiaomei.assistant.HostSettingsActivity;

public abstract class BaseSettingFragment extends Fragment {

    private HostSettingsActivity mSettingsHostActivity = null;
    @Nullable
    private String mTitle = null;
    @Nullable
    private String mSubtitle = null;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mSettingsHostActivity = (HostSettingsActivity) requireActivity();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mSettingsHostActivity = null;
    }

    @Nullable
    protected HostSettingsActivity getSettingsHostActivity() {
        return mSettingsHostActivity;
    }

    @NonNull
    protected HostSettingsActivity requireSettingsHostActivity() {
        if (mSettingsHostActivity == null) {
            throw new IllegalStateException("Host settings activity is null");
        }
        return mSettingsHostActivity;
    }

    public void finishFragment() {
        requireSettingsHostActivity().finishFragment(this);
    }

    @Nullable
    public String getTitle() {
        return mTitle;
    }

    @Nullable
    public String getSubtitle() {
        return mSubtitle;
    }

    protected void setTitle(@Nullable String title) {
        mTitle = title;
        if (mSettingsHostActivity != null) {
            mSettingsHostActivity.requestInvalidateActionBar();
        }
    }

    protected void setSubtitle(@Nullable String subtitle) {
        mSubtitle = subtitle;
        if (mSettingsHostActivity != null) {
            mSettingsHostActivity.requestInvalidateActionBar();
        }
    }
}
