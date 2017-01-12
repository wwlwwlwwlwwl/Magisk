package com.topjohnwu.magisk.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.topjohnwu.magisk.R;
import com.topjohnwu.magisk.module.BaseModule;
import com.topjohnwu.magisk.module.Module;
import com.topjohnwu.magisk.module.Repo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ModuleHelper {
    private static final String MAGISK_PATH = "/magisk";
    public static final String FILE_KEY = "RepoMap";
    private static final String REPO_KEY = "repomap";
    public static final String VERSION_KEY = "version";
    public static final String ETAG_KEY = "ETag";
    private static final int DATABASE_VER = 1;

    private static ValueSortedMap<String, Repo> repoMap = new ValueSortedMap<>();
    private static ValueSortedMap<String, Module> moduleMap = new ValueSortedMap<>();


    public static void createModuleMap() {
        Logger.dev("ModuleHelper: Loading modules");

        moduleMap.clear();

        for (String path : Utils.getModList(MAGISK_PATH)) {
            Logger.dev("ModuleHelper: Adding modules from " + path);
            Module module;
            try {
                module = new Module(path);
                moduleMap.put(module.getId(), module);
            } catch (BaseModule.CacheModException ignored) {}
        }

        Logger.dev("ModuleHelper: Module load done");
    }

    public static void createRepoMap(Context context) {
        Logger.dev("ModuleHelper: Loading repos");

        SharedPreferences prefs = context.getSharedPreferences(FILE_KEY, Context.MODE_PRIVATE);

        repoMap.clear();

        Gson gson = new Gson();
        String jsonString;

        int cachedVersion = prefs.getInt(VERSION_KEY, 0);
        if (cachedVersion != DATABASE_VER) {
            // Ignore incompatible cached database
            jsonString = null;
        } else {
            jsonString = prefs.getString(REPO_KEY, null);
        }

        ValueSortedMap<String, Repo> cached = null;

        if (jsonString != null) {
            cached = gson.fromJson(jsonString, new TypeToken<ValueSortedMap<String, Repo>>(){}.getType());
        }

        if (cached == null) {
            cached = new ValueSortedMap<>();
        }

        // Get cached ETag to add in the request header
        String etag = prefs.getString(ETAG_KEY, "");
        HashMap<String, String> header = new HashMap<>();
        header.put("If-None-Match", etag);

        // Making a request to main URL for repo info
        jsonString = WebService.request(
                context.getString(R.string.url_main), WebService.GET, null, header, false);

        if (!jsonString.isEmpty()) {
            try {
                JSONArray jsonArray = new JSONArray(jsonString);
                // If it gets to this point, the response is valid, update ETag
                etag = WebService.getLastResponseHeader().get(ETAG_KEY).get(0);
                // Maybe bug in Android build tools, sometimes the ETag has crap in it...
                etag = etag.substring(etag.indexOf('\"'), etag.lastIndexOf('\"') + 1);

                // Update repo info
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject jsonobject = jsonArray.getJSONObject(i);
                    String id = jsonobject.getString("description");
                    String name = jsonobject.getString("name");
                    String lastUpdate = jsonobject.getString("pushed_at");
                    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                    Date updatedDate;
                    try {
                        updatedDate = format.parse(lastUpdate);
                    } catch (ParseException e) {
                        continue;
                    }
                    Repo repo = cached.get(id);
                    try {
                        if (repo == null) {
                            Logger.dev("ModuleHelper: Create new repo " + id);
                            repo = new Repo(context, name, updatedDate);
                        } else {
                            Logger.dev("ModuleHelper: Update cached repo " + id);
                            repo.update(updatedDate);
                        }
                        if (repo.getId() != null) {
                            repoMap.put(id, repo);
                        }
                    } catch (BaseModule.CacheModException ignored) {}
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        } else {
            // Use cached if no internet or no updates
            Logger.dev("ModuleHelper: No updates, use cached");
            repoMap.putAll(cached);
        }

        prefs.edit()
                .putInt(VERSION_KEY, DATABASE_VER)
                .putString(REPO_KEY, gson.toJson(repoMap))
                .putString(ETAG_KEY, etag)
                .apply();

        Logger.dev("ModuleHelper: Repo load done");
    }

    public static void getModuleList(List<Module> moduleList) {
        moduleList.clear();
        moduleList.addAll(moduleMap.values());
    }

    public static void getRepoLists(List<Repo> update, List<Repo> installed, List<Repo> others) {
        update.clear();
        installed.clear();
        others.clear();
        for (Repo repo : repoMap.values()) {
            Module module = moduleMap.get(repo.getId());
            if (module != null) {
                if (repo.getVersionCode() > module.getVersionCode()) {
                    update.add(repo);
                } else {
                    installed.add(repo);
                }
            } else {
                others.add(repo);
            }
        }
    }

    private static class ValueSortedMap<K, V extends Comparable<? super V>> extends HashMap<K, V> {

        private List<V> sorted = new ArrayList<>();

        @NonNull
        @Override
        public Collection<V> values() {
            if (sorted.isEmpty()) {
                sorted.addAll(super.values());
                Collections.sort(sorted);
            }
            return sorted;
        }

        @Override
        public V put(K key, V value) {
            sorted.clear();
            return super.put(key, value);
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> m) {
            sorted.clear();
            super.putAll(m);
        }

        @Override
        public V remove(Object key) {
            sorted.clear();
            return super.remove(key);
        }
    }
}
