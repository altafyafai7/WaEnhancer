package com.wmods.wppenhacer.xposed.features.listeners;

import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class MenuStatusListener extends Feature {

    public static HashSet<onMenuItemStatusListener> menuStatuses = new HashSet<>();

    public MenuStatusListener(@NonNull ClassLoader classLoader, @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {

        var menuStatusMethod = Unobfuscator.loadStatusContextMenuMethod(classLoader);
        logDebug("MenuStatus method: " + menuStatusMethod.getName());
        var menuManagerClass = Unobfuscator.loadMenuManagerClass(classLoader);

        Class<?> StatusPlaybackBaseFragmentClass = classLoader.loadClass("com.whatsapp.status.playback.fragment.StatusPlaybackBaseFragment");
        Class<?> StatusPlaybackContactFragmentClass = classLoader.loadClass("com.whatsapp.status.playback.fragment.StatusPlaybackContactFragment");
        
        var listStatusField = Unobfuscator.loadStatusListField(classLoader);
        var indexStatusField = Unobfuscator.loadStatusIndexField(classLoader);

        XposedBridge.hookMethod(menuStatusMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("MenuStatus: onCreateContextMenu called in " + param.thisObject.getClass().getName());
                
                Object fragmentInstance = null;
                if (param.thisObject != null) {
                    if (StatusPlaybackContactFragmentClass.isInstance(param.thisObject) || StatusPlaybackBaseFragmentClass.isInstance(param.thisObject)) {
                        fragmentInstance = param.thisObject;
                    }
                }
                
                if (fragmentInstance == null) {
                    var fieldObjects = Arrays.stream(param.method.getDeclaringClass().getDeclaredFields()).map(field -> ReflectionUtils.getObjectField(field, param.thisObject)).filter(Objects::nonNull).collect(Collectors.toList());
                    fragmentInstance = fieldObjects.stream().filter(StatusPlaybackBaseFragmentClass::isInstance).findFirst().orElse(null);
                }
                
                if (fragmentInstance == null) {
                    XposedBridge.log("MenuStatus: Could not find StatusPlaybackFragment instance");
                    return;
                }

                Menu menu = (Menu) param.args[0];
                
                Object indexObj = ReflectionUtils.getObjectField(indexStatusField, fragmentInstance);
                if (!(indexObj instanceof Integer)) {
                    XposedBridge.log("MenuStatus: Could not get index from StatusIndex field");
                    return;
                }
                int index = (int) indexObj;
                List listStatus = (List) ReflectionUtils.getObjectField(listStatusField, fragmentInstance);
                
                if (listStatus == null || index < 0 || index >= listStatus.size()) {
                    XposedBridge.log("MenuStatus: Invalid index (" + index + ") or null list");
                    return;
                }

                var object = listStatus.get(index);
                if (object == null) return;
                if (!FMessageWpp.TYPE.isInstance(object)) {
                    var fMessageField = ReflectionUtils.getFieldByExtendType(object.getClass(), FMessageWpp.TYPE);
                    if (fMessageField != null) {
                        object = ReflectionUtils.getObjectField(fMessageField, object);
                    }
                }

                if (object == null || !FMessageWpp.TYPE.isInstance(object)) {
                    XposedBridge.log("MenuStatus: Could not extract FMessage from object: " + (object != null ? object.getClass().getName() : "null"));
                    return;
                }

                var fMessage = new FMessageWpp(object);

                for (onMenuItemStatusListener menuStatus : menuStatuses) {
                    var menuItem = menuStatus.addMenu(menu, fMessage);
                    if (menuItem == null) continue;
                    menuItem.setOnMenuItemClickListener(item -> {
                        menuStatus.onClick(item, fragmentInstance, fMessage);
                        return true;
                    });
                }
            }
        });
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Menu Status";
    }

    public abstract static class onMenuItemStatusListener {

        public abstract MenuItem addMenu(Menu menu, FMessageWpp fMessage);

        public abstract void onClick(MenuItem item, Object fragmentInstance, FMessageWpp fMessageWpp);
    }
}
