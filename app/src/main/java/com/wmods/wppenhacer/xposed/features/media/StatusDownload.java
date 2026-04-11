package com.wmods.wppenhacer.xposed.features.media;

import static com.wmods.wppenhacer.xposed.features.listeners.MenuStatusListener.menuStatuses;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.features.listeners.MenuStatusListener;
import com.wmods.wppenhacer.xposed.utils.MimeTypeUtils;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class StatusDownload extends Feature {

    // Use dedicated unique IDs that won't collide with WhatsApp's
    // dynamically-resolved
    // string resource IDs (which are unpredictable across WA versions/obfuscation
    // passes).
    private static final int MENU_ID_DOWNLOAD = 0x7EAD0001;
    private static final int MENU_ID_SHARE_STATUS = 0x7EAD0002;

    public StatusDownload(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    public void doHook() throws Exception {
        if (!prefs.getBoolean("downloadstatus", false))
            return;

        var downloadStatus = new MenuStatusListener.onMenuItemStatusListener() {

            @Override
            public MenuItem addMenu(Menu menu, FMessageWpp fMessage) {
                // Guard against duplicate entries using our own unique ID
                if (menu.findItem(MENU_ID_DOWNLOAD) != null)
                    return null;
                if (fMessage.getKey().isFromMe)
                    return null;
                if (!fMessage.isMediaFile())
                    return null;
                return menu.add(0, MENU_ID_DOWNLOAD, 0, ResId.string.download);
            }

            @Override
            public void onClick(MenuItem item, Object fragmentInstance, FMessageWpp fMessageWpp) {
                downloadFile(fMessageWpp);
            }
        };
        menuStatuses.add(downloadStatus);

        var sharedMenu = new MenuStatusListener.onMenuItemStatusListener() {

            @Override
            public MenuItem addMenu(Menu menu, FMessageWpp fMessage) {
                if (fMessage.getKey().isFromMe)
                    return null;
                // Guard against duplicate entries using our own unique ID
                if (menu.findItem(MENU_ID_SHARE_STATUS) != null)
                    return null;
                return menu.add(0, MENU_ID_SHARE_STATUS, 0, ResId.string.share_as_status);
            }

            @Override
            public void onClick(MenuItem item, Object fragmentInstance, FMessageWpp fMessageWpp) {
                sharedStatus(fMessageWpp);
            }
        };
        menuStatuses.add(sharedMenu);

        // Direct Icon Injection
        var viewButtonMethod = Unobfuscator.loadBlueOnReplayViewButtonMethod(classLoader);
        var viewStatusField = Unobfuscator.loadBlueOnReplayViewButtonOutSideField(classLoader);

        XposedBridge.hookMethod(viewButtonMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Field fMessageField = ReflectionUtils.getFieldByExtendType(viewStatusField.getDeclaringClass(), FMessageWpp.TYPE);
                Object fMessageObj = ReflectionUtils.getObjectField(fMessageField, param.thisObject);
                if (fMessageObj == null) {
                    Object instance = ReflectionUtils.getObjectField(viewStatusField, param.thisObject);
                    fMessageField = ReflectionUtils.findFieldUsingFilterIfExists(instance.getClass(), field1 -> FMessageWpp.TYPE.isAssignableFrom(field1.getType()));
                    if (fMessageField != null) {
                        fMessageObj = ReflectionUtils.getObjectField(fMessageField, instance);
                    }
                }
                if (fMessageObj == null) return;

                FMessageWpp fMessage = new FMessageWpp(fMessageObj);
                if (fMessage.getKey().isFromMe) return;

                View view = (View) param.getResult();
                LinearLayout contentView = (LinearLayout) view.findViewById(Utils.getID("bottom_sheet", "id"));
                if (contentView == null) return;

                contentView.setOrientation(LinearLayout.HORIZONTAL);

                // Share Icon
                ImageView shareIcon = new ImageView(view.getContext());
                LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(Utils.dipToPixels(32), Utils.dipToPixels(32));
                shareParams.gravity = Gravity.CENTER_VERTICAL;
                shareParams.setMargins(Utils.dipToPixels(5), Utils.dipToPixels(5), 0, 0);
                shareIcon.setLayoutParams(shareParams);
                shareIcon.setImageResource(Utils.getID("ic_action_share", "drawable"));
                shareIcon.setBackground(getIconBackground());
                shareIcon.setOnClickListener(v -> sharedStatus(fMessage));
                contentView.addView(shareIcon, 0);

                // Download Icon (only if media)
                if (fMessage.isMediaFile()) {
                    ImageView downloadIcon = new ImageView(view.getContext());
                    LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(Utils.dipToPixels(32), Utils.dipToPixels(32));
                    downloadParams.gravity = Gravity.CENTER_VERTICAL;
                    downloadParams.setMargins(Utils.dipToPixels(5), Utils.dipToPixels(5), 0, 0);
                    downloadIcon.setLayoutParams(downloadParams);
                    downloadIcon.setImageResource(Utils.getID("download", "drawable"));
                    downloadIcon.setBackground(getIconBackground());
                    downloadIcon.setOnClickListener(v -> downloadFile(fMessage));
                    contentView.addView(downloadIcon, 0);
                }
            }
        });
    }

    private GradientDrawable getIconBackground() {
        GradientDrawable border = new GradientDrawable();
        border.setShape(GradientDrawable.RECTANGLE);
        border.setStroke(1, Color.WHITE);
        border.setCornerRadius(20);
        border.setColor(Color.parseColor("#80000000"));
        return border;
    }

    private void sharedStatus(FMessageWpp fMessageWpp) {
        try {
            if (!fMessageWpp.isMediaFile()) {
                // Text-only status: open the text status composer
                Intent intent = new Intent();
                Class clazz;
                try {
                    clazz = Unobfuscator.getClassByName("TextStatusComposerActivity", classLoader);
                } catch (Exception ignored) {
                    clazz = Unobfuscator.getClassByName("ConsolidatedStatusComposerActivity", classLoader);
                    intent.putExtra("status_composer_mode", 2);
                }
                intent.setClassName(Utils.getApplication().getPackageName(), clazz.getName());
                intent.putExtra("android.intent.extra.TEXT", fMessageWpp.getMessageStr());
                WppCore.getCurrentActivity().startActivity(intent);
                return;
            }

            var file = fMessageWpp.getMediaFile();
            if (file == null) {
                Utils.showToast(Utils.getApplication().getString(ResId.string.download_not_available), 1);
                return;
            }

            // Build a content:// URI via FileProvider so Android 7+ doesn't block it.
            // The FileProvider in AndroidManifest covers external-path "." so all WA
            // media files under external storage are reachable.
            Uri mediaUri;
            try {
                String authority = Utils.getApplication().getPackageName() + ".fileprovider";
                mediaUri = FileProvider.getUriForFile(Utils.getApplication(), authority, file);
            } catch (IllegalArgumentException e) {
                // Fallback: if file is outside FileProvider paths, use file:// URI
                // (works on root devices and Android < 7, better than silently failing)
                XposedBridge
                        .log("WaEnhancer: FileProvider failed for " + file.getAbsolutePath() + ": " + e.getMessage());
                mediaUri = Uri.fromFile(file);
            }

            Intent intent = new Intent();
            var clazz = Unobfuscator.getClassByName("MediaComposerActivity", classLoader);
            intent.setClassName(Utils.getApplication().getPackageName(), clazz.getName());
            intent.putExtra("jids", new ArrayList<>(Collections.singleton("status@broadcast")));
            intent.putExtra("android.intent.extra.STREAM", new ArrayList<>(Collections.singleton(mediaUri)));
            // Carry caption text if present
            String caption = fMessageWpp.getMessageStr();
            if (!TextUtils.isEmpty(caption)) {
                intent.putExtra("android.intent.extra.TEXT", caption);
            }
            // Grant read permission to WhatsApp so it can read the content:// URI
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            WppCore.getCurrentActivity().startActivity(intent);
        } catch (Throwable e) {
            XposedBridge.log("WaEnhancer: sharedStatus error: " + e.getMessage());
            Utils.showToast(e.getMessage(), Toast.LENGTH_SHORT);
        }
    }

    private void downloadFile(FMessageWpp fMessage) {
        try {
            var file = fMessage.getMediaFile();
            if (file == null) {
                Utils.showToast(Utils.getApplication().getString(ResId.string.download_not_available), 1);
                return;
            }
            var userJid = fMessage.getUserJid();
            var fileType = file.getName().substring(file.getName().lastIndexOf(".") + 1);
            var destination = getStatusDestination(file);
            var name = Utils.generateName(userJid, fileType);
            var error = Utils.copyFile(file, destination, name);
            if (TextUtils.isEmpty(error)) {
                Utils.showToast(Utils.getApplication().getString(ResId.string.saved_to) + destination,
                        Toast.LENGTH_SHORT);
            } else {
                Utils.showToast(
                        Utils.getApplication().getString(ResId.string.error_when_saving_try_again) + ": " + error,
                        Toast.LENGTH_SHORT);
            }
        } catch (Throwable e) {
            Utils.showToast(e.getMessage(), Toast.LENGTH_SHORT);
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Download Status";
    }

    @NonNull
    private String getStatusDestination(@NonNull File f) throws Exception {
        var fileName = f.getName().toLowerCase();
        var mimeType = MimeTypeUtils.getMimeTypeFromExtension(fileName);
        var folderPath = "";
        if (mimeType.contains("video")) {
            folderPath = "Status Videos";
        } else if (mimeType.contains("image")) {
            folderPath = "Status Images";
        } else if (mimeType.contains("audio")) {
            folderPath = "Status Sounds";
        } else {
            folderPath = "Status Media";
        }
        return Utils.getDestination(folderPath);
    }

}