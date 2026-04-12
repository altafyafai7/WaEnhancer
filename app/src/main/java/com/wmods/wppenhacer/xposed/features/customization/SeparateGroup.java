package com.wmods.wppenhacer.xposed.features.customization;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.BaseAdapter;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.db.MessageStore;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.core.devkit.UnobfuscatorCache;
import com.wmods.wppenhacer.xposed.utils.DebugUtils;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SeparateGroup extends Feature {

    public static final int CHATS = 200;
    public static final int STATUS = 300;
    //    public static final int CALLS = 400;
//    public static final int COMMUNITY = 600;
    public static final int GROUPS = 500;
    public static ArrayList<Integer> tabs = new ArrayList<>();
    public static HashMap<Integer, Object> tabInstances = new HashMap<>();

    public SeparateGroup(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    public void doHook() throws Exception {

        var cFragClass = XposedHelpers.findClass("com.whatsapp.conversationslist.ConversationsFragment", classLoader);
        var homeActivityClass = WppCore.getHomeActivityClass(classLoader);

        if (!prefs.getBoolean("separategroups", false)) {
            XposedBridge.log("SeparateGroup: Feature is disabled in settings");
            return;
        }

        XposedBridge.log("SeparateGroup: Initializing hooks...");

        // Modifying tab list order
        hookTabList(homeActivityClass);

        // Setting group icon
        hookTabIcon();

        // Setting up fragments
        hookTabInstance(cFragClass);

        // Setting group tab name
        hookTabName();

        // Setting tab count
        hookTabCount();
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Separate Group";
    }

    private void hookTabCount() throws Exception {

        Method runMethod = Unobfuscator.loadTabCountMethod(classLoader);
        XposedBridge.log("SeparateGroup: Hooking TabCount method: " + runMethod.getName());

        Method enableCountMethod = Unobfuscator.loadEnableCountTabMethod(classLoader);
        Constructor<?> constructor1 = Unobfuscator.loadEnableCountTabConstructor1(classLoader);
        Constructor<?> constructor2 = Unobfuscator.loadEnableCountTabConstructor2(classLoader);
        Constructor<?> constructor3 = Unobfuscator.loadEnableCountTabConstructor3(classLoader);
        constructor3.setAccessible(true);

        XposedBridge.hookMethod(enableCountMethod, new XC_MethodHook() {
            @Override
            @SuppressLint({"Range", "Recycle"})
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                int indexTab = (int) param.args[2];
                if (indexTab == tabs.indexOf(CHATS)) {
                    XposedBridge.log("SeparateGroup: Updating tab badge counts at index: " + indexTab);
                    int chatCount = 0;
                    int groupCount = 0;
                    synchronized (SeparateGroup.class) {
                        android.database.sqlite.SQLiteDatabase db = MessageStore.getInstance().getDatabase();
                        String sql = "SELECT * FROM chat WHERE unseen_message_count != 0";
                        android.database.Cursor cursor = db.rawQuery(sql, null);
                        while (cursor.moveToNext()) {
                            int jid = cursor.getInt(cursor.getColumnIndex("jid_row_id"));
                            int groupType = cursor.getInt(cursor.getColumnIndex("group_type"));
                            int archived = cursor.getInt(cursor.getColumnIndex("archived"));
                            int chatLocked = cursor.getInt(cursor.getColumnIndex("chat_lock"));
                            if (archived != 0 || (groupType != 0 && groupType != 6) || chatLocked != 0)
                                continue;
                            String sql2 = "SELECT * FROM jid WHERE _id == ?";
                            android.database.Cursor cursor1 = db.rawQuery(sql2, new String[]{String.valueOf(jid)});
                            if (!cursor1.moveToFirst()) continue;
                            String server = cursor1.getString(cursor1.getColumnIndex("server"));
                            if (server.equals("g.us")) {
                                groupCount++;
                            } else {
                                chatCount++;
                            }
                            cursor1.close();
                        }
                        cursor.close();
                    }
                    if (tabs.contains(CHATS) && tabInstances.containsKey(CHATS)) {
                        Object instance12 = chatCount <= 0 ? constructor3.newInstance() : constructor2.newInstance(chatCount);
                        Object instance22 = constructor1.newInstance(instance12);
                        param.args[1] = instance22;
                    }
                    if (tabs.contains(GROUPS) && tabInstances.containsKey(GROUPS)) {
                        Object instance2 = groupCount <= 0 ? constructor3.newInstance() : constructor2.newInstance(groupCount);
                        Object instance1 = constructor1.newInstance(instance2);
                        enableCountMethod.invoke(param.thisObject, param.args[0], instance1, tabs.indexOf(GROUPS));
                    }
                }
            }
        });
    }

    private void hookTabIcon() throws Exception {
        Method iconTabMethod = Unobfuscator.loadIconTabMethod(classLoader);
        Method menuAddAndroidX = Unobfuscator.loadAddMenuAndroidX(classLoader);
        XposedBridge.log("SeparateGroup: Hooking IconTab method: " + iconTabMethod.getName());

        XposedBridge.hookMethod(iconTabMethod, new XC_MethodHook() {

                    private XC_MethodHook.Unhook hooked;

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("SeparateGroup: iconTabMethod beforeHookedMethod");
                        hooked = XposedBridge.hookMethod(menuAddAndroidX, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.args.length > 2 && ((int) param.args[1]) == GROUPS) {
                                    MenuItem menuItem = (MenuItem) param.getResult();
                                    if (menuItem == null) return;
                                    
                                    int iconId = Utils.getID("home_tab_communities_selector", "drawable");
                                    if (iconId <= 0) iconId = Utils.getID("ic_action_group", "drawable");
                                    if (iconId <= 0) iconId = Utils.getID("home_tab_chats_selector", "drawable");
                                    
                                    XposedBridge.log("SeparateGroup: Setting icon for GROUPS tab. ID: " + iconId);
                                    if (iconId > 0) {
                                        menuItem.setIcon(iconId);
                                    }
                                }
                            }
                        });
                    }

                    @SuppressLint("ResourceType")
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (hooked != null) {
                            hooked.unhook();
                        }
                    }
                }
        );
    }

    @SuppressLint("ResourceType")
    private void hookTabName() throws Exception {
        Method tabNameMethod = Unobfuscator.loadTabNameMethod(classLoader);
        XposedBridge.hookMethod(tabNameMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                int tab = (int) param.args[0];
                if (tab == GROUPS) {
                    param.setResult(UnobfuscatorCache.getInstance().getString("groups"));
                }
            }
        });
    }

    private void hookTabInstance(Class<?> cFrag) throws Exception {
        Method getTabMethod = Unobfuscator.loadGetTabMethod(classLoader);
        XposedBridge.log("SeparateGroup: Hooking GetTab method: " + getTabMethod.getName());

        Method methodTabInstance = Unobfuscator.loadTabFragmentMethod(classLoader);
        XposedBridge.log("SeparateGroup: Hooking TabFragment list method: " + methodTabInstance.getName());

        Constructor<?> recreateFragmentMethod = Unobfuscator.loadRecreateFragmentConstructor(classLoader);

        Pattern pattern = Pattern.compile("android:switcher:\\d+:(\\d+)");

        Class<?> FragmentClass = Unobfuscator.loadFragmentClass(classLoader);

        XposedBridge.hookMethod(recreateFragmentMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                String string = "";
                if (param.args[0] instanceof Bundle) {
                    Bundle bundle = (Bundle) param.args[0];
                    Object state = bundle.getParcelable("state");
                    if (state == null) return;
                    string = state.toString();
                } else {
                    string = param.args[2].toString();
                }
                Matcher matcher = pattern.matcher(string);
                if (matcher.find()) {
                    int tabId = Integer.parseInt(matcher.group(1));
                    if (tabId == GROUPS || tabId == CHATS) {
                        Field fragmentField = ReflectionUtils.getFieldByType(param.thisObject.getClass(), FragmentClass);
                        Object convFragment = ReflectionUtils.getObjectField(fragmentField, param.thisObject);
                        XposedBridge.log("SeparateGroup: Recreated fragment for tab ID: " + tabId + " -> " + (convFragment != null ? convFragment.getClass().getName() : "null"));
                        tabInstances.put(tabId, convFragment);
                    }
                }
            }
        });

        XposedBridge.hookMethod(getTabMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                int index = (int) param.args[0];
                if (index >= tabs.size()) return;
                int tabId = tabs.get(index).intValue();
                if (tabId == GROUPS || tabId == CHATS) {
                    Object convFragment = cFrag.newInstance();
                    XposedBridge.log("SeparateGroup: Created new fragment instance for tab ID: " + tabId);
                    param.setResult(convFragment);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                int index = (int) param.args[0];
                if (index >= tabs.size()) return;
                int tabId = tabs.get(index).intValue();
                if (tabId == GROUPS || tabId == CHATS) {
                    XposedBridge.log("SeparateGroup: Storing fragment instance for tab ID: " + tabId);
                    tabInstances.put(tabId, param.getResult());
                }
            }
        });

        XposedBridge.hookMethod(methodTabInstance, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                List<?> chatsList = (List<?>) param.getResult();
                if (chatsList == null) return;
                
                List<?> resultList = filterChat(param.thisObject, chatsList);
                if (resultList != chatsList) {
                    XposedBridge.log("SeparateGroup: Applied filter to chat list. Size: " + chatsList.size() + " -> " + resultList.size());
                    param.setResult(resultList);
                }
            }
        });

        Method fabintMethod = Unobfuscator.loadFabMethod(classLoader);
        XposedBridge.hookMethod(fabintMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (Objects.equals(tabInstances.get(GROUPS), param.thisObject)) {
                    param.setResult(GROUPS);
                }
            }
        });

        Method publishResultsMethod = Unobfuscator.loadGetFiltersMethod(classLoader);
        XposedBridge.hookMethod(publishResultsMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object filters = param.args[1];
                List<?> chatsList = (List<?>) XposedHelpers.getObjectField(filters, "values");
                if (chatsList == null) return;

                Field baseField = ReflectionUtils.getFieldByExtendType(publishResultsMethod.getDeclaringClass(), BaseAdapter.class);
                if (baseField == null) return;
                Field convField = ReflectionUtils.getFieldByType(baseField.getType(), cFrag);
                if (convField == null) return;
                Object thiz = ReflectionUtils.getObjectField(convField, ReflectionUtils.getObjectField(baseField, param.thisObject));
                if (thiz == null) return;
                
                List<?> resultList = filterChat(thiz, chatsList);
                if (resultList != chatsList) {
                    XposedBridge.log("SeparateGroup: Applied filter to search results. Size: " + chatsList.size() + " -> " + resultList.size());
                    XposedHelpers.setObjectField(filters, "values", resultList);
                    XposedHelpers.setIntField(filters, "count", resultList.size());
                }
            }
        });
    }

    private List<?> filterChat(Object thiz, List<?> chatsList) {
        Object tabChat = tabInstances.get(CHATS);
        Object tabGroup = tabInstances.get(GROUPS);
        
        boolean isChatTab = Objects.equals(tabChat, thiz);
        boolean isGroupTab = Objects.equals(tabGroup, thiz);

        if (!isChatTab && !isGroupTab) {
            return chatsList;
        }
        
        XposedBridge.log("SeparateGroup: Filtering list for " + (isGroupTab ? "GROUPS" : "CHATS") + " tab");
        ArrayListFilter editableChatList = new ArrayListFilter(isGroupTab);
        editableChatList.addAll(chatsList);
        return editableChatList;
    }

    private void hookTabList(@NonNull Class<?> home) throws Exception {
        Method onCreateTabList = Unobfuscator.loadTabListMethod(classLoader);
        XposedBridge.hookMethod(onCreateTabList, new XC_MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!prefs.getBoolean("separategroups", false)) return;

                // Try to find the tabs list field dynamically in both HomeActivity and param.thisObject
                List<Object> searchObjects = new ArrayList<>();
                if (param.thisObject != null) searchObjects.add(param.thisObject);
                searchObjects.add(null); // To check static fields in HomeActivity and param.thisObject.getClass()

                for (Object obj : searchObjects) {
                    Class<?> targetClass = (obj != null) ? obj.getClass() : home;
                    Field[] fields = ReflectionUtils.findAllFieldsUsingFilter(targetClass, f -> List.class.isAssignableFrom(f.getType()));
                    
                    for (Field field : fields) {
                        try {
                            field.setAccessible(true);
                            ArrayList<Integer> value = (ArrayList<Integer>) field.get(obj);
                            if (value != null && !value.isEmpty() && (value.contains(CHATS) || value.contains(STATUS))) {
                                tabs = value;
                                XposedBridge.log("SeparateGroup: Found tabs list in field: " + targetClass.getName() + "->" + field.getName());
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                    if (tabs != null) break;
                }

                if (tabs == null) {
                    XposedBridge.log("SeparateGroup: CRITICAL - Tabs list not found after scanning fields");
                    return;
                }

                if (!tabs.contains(GROUPS)) {
                    XposedBridge.log("SeparateGroup: Adding GROUPS tab to list");
                    tabs.add(tabs.isEmpty() ? 0 : 1, GROUPS);
                }
            }
        });
    }


    public static class ArrayListFilter extends ArrayList<Object> {

        private final boolean isGroup;

        public ArrayListFilter(boolean isGroup) {
            this.isGroup = isGroup;
        }


        @Override
        public void add(int index, Object element) {
            if (checkGroup(element)) {
                super.add(index, element);
            }
        }

        @Override
        public boolean add(Object object) {
            if (checkGroup(object)) {
                return super.add(object);
            }
            return true;
        }

        @Override
        public boolean addAll(@NonNull Collection<?> c) {
            for (Object chat : c) {
                if (checkGroup(chat)) {
                    super.add(chat);
                }
            }
            return true;
        }

        private boolean checkGroup(Object chat) {
            // Strategy 1: Find JID field by type
            Field jidField = ReflectionUtils.findFieldUsingFilterIfExists(chat.getClass(), f -> FMessageWpp.UserJid.TYPE_JID.isAssignableFrom(f.getType()));
            Object jidObject = null;
            
            if (jidField != null) {
                jidObject = ReflectionUtils.getObjectField(jidField, chat);
            }

            // Strategy 2: Aggressive scan if type-based discovery failed or returned null
            if (jidObject == null) {
                for (Field field : chat.getClass().getDeclaredFields()) {
                    if (field.getType().isPrimitive()) continue;
                    Object obj = ReflectionUtils.getObjectField(field, chat);
                    if (obj != null && XposedHelpers.findMethodExactIfExists(obj.getClass(), "getServer") != null) {
                        jidObject = obj;
                        break;
                    }
                }
            }

            if (jidObject == null) {
                XposedBridge.log("SeparateGroup: Could not find JID in chat object of class " + chat.getClass().getName());
                return true;
            }

            FMessageWpp.UserJid userJid = new FMessageWpp.UserJid(jidObject);
            boolean isGroupJid = userJid.isGroup() || userJid.isBroadcast();
            
            if (isGroup) return isGroupJid;
            return !isGroupJid;
        }
    }

}
