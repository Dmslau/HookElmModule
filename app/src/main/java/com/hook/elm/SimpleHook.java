package com.hook.elm;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.URL;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SimpleHook implements IXposedHookLoadPackage {
    String host = "http://";
    String client_id = "";
    String client_secret = "";


    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 拦截特定包名的应用
        if (!lpparam.packageName.equals("me.ele"))
            return;

        // 目标页面的类名和方法名
        String targetClassName = "me.ele.account.ui.info.SettingMoreActivity2";
        String targetMethodName = "onCreate";
        // Hook onCreate 方法
        XposedHelpers.findAndHookMethod(targetClassName, lpparam.classLoader, targetMethodName, Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                //获取当前的cookie
                String cookie = processCookie((String) XposedHelpers.callStaticMethod(XposedHelpers.findClass("anetwork.channel.cookie.CookieManager", lpparam.classLoader), "getCookie", "https://app-monitor.ele.me/log"));

                // 复制到剪贴板
                copy(cookie, (Context) param.thisObject);

                //发送到服务器
                sendToServer(cookie);
            }
        });
    }


    private String processCookie(String cookie) {
        // 处理cookie，只保留cookie2、USERID、SID，并用;隔开
        String[] cookies = cookie.split(";");
        String cookie2 = "";
        String USERID = "";
        String SID = "";


        for (String c : cookies) {
            if (c.contains("cookie2")) {
                cookie2 = c;
            } else if (c.contains("USERID")) {
                USERID = c;
            } else if (c.contains("SID")) {
                SID = c;
            }

        }

        cookie = cookie2 + ";" + USERID + ";" + SID + ";";

        return cookie;
    }

    private void copy(String str, Context context) throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        ((ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("cookie", str.replace(" ", "").trim()));
        //Toast.makeText(context, "复制成功"+str, Toast.LENGTH_SHORT).show();
    }


    //toast提示，context自动获取
    private void toast(String msg) throws Exception  {
        Toast.makeText((Context) XposedHelpers.findClass("android.app.ActivityThread", null).getDeclaredMethod("currentApplication").invoke(null), msg, Toast.LENGTH_SHORT).show();
    }

    private void sendToServer(String cookie) {
        String[] params = cookie.split(";");
        String SID = params[2];
        String USERID = params[1];
        String cookie2 = params[0];
        new GetTokenTask().execute(SID, USERID, cookie2);
    }


    private class GetTokenTask extends AsyncTask<String, String, String> {

        protected String doInBackground(String... params) {
            String sid = params[0];
            String userid = params[1];
            String cookie2 = params[2];
            //去除空格
            cookie2 = cookie2.replace(" ", "");
            sid = sid.replace(" ", "");
            userid = userid.replace(" ", "");

            // 获取 Token
            String token = getToken();

            // 使用 Token 请求获取环境变量
            JSONObject envsJson = getEnvs(token);

            // 匹配 env 和 userid，并根据匹配结果更新或新增
            matchAndUpdateEnv(cookie2, sid, userid, envsJson, token);
            try {
               // toast("总流程正常结束");
            }catch (Exception e) {}
            return "Done";
        }

        // 获取 Token 的方法
        private String getToken() {
            String token = "";
            String url = host + "/open/auth/token?client_id=" + client_id + "&client_secret=" + client_secret;

            try {
                URL obj = new URL(url);
                HttpURLConnection con = (HttpURLConnection) obj.openConnection();
                con.setRequestMethod("GET");
                int responseCode = con.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
                    String inputLine;
                    StringBuilder response = new StringBuilder();
                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                    in.close();
                    JSONObject jsonObj = new JSONObject(response.toString());
                    String code = jsonObj.getString("code");
                    if (code.equals("200")) {
                        token = jsonObj.getJSONObject("data").getString("token");
                   //     toast("token获取正常");
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return token;
        }

        // 使用 Token 请求获取环境变量的方法
        private JSONObject getEnvs(String token) {
            JSONObject envsJson = null;
            try {
                URL envsUrl = new URL(host + "/open/envs?searchValue=&t=" + System.currentTimeMillis() / 1000);
                HttpURLConnection envsConnection = (HttpURLConnection) envsUrl.openConnection();
                envsConnection.setRequestMethod("GET");
                envsConnection.setRequestProperty("Authorization", "Bearer " + token);

                int envsResponseCode = envsConnection.getResponseCode();
                if (envsResponseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader envsReader = new BufferedReader(new InputStreamReader(envsConnection.getInputStream()));
                    StringBuilder envsResponse = new StringBuilder();
                    String envsInputLine;
                    while ((envsInputLine = envsReader.readLine()) != null) {
                        envsResponse.append(envsInputLine);
                    }
                    envsReader.close();

                    envsJson = new JSONObject(envsResponse.toString());
               //     toast("env处理成功");
                }
             //   toast("env获取结束");
            } catch (Exception e) {
                e.printStackTrace();
            }

            return envsJson;
        }

        // 匹配 env 和 userid 的方法，并根据匹配结果更新或新增的方法
        private String matchAndUpdateEnv(String cookie2, String sid, String userid, JSONObject envsJson, String token) {
            try {
                if (envsJson != null && envsJson.getInt("code") == 200) {
                    JSONArray envsData = envsJson.getJSONArray("data");

                    for (int i = 0; i < envsData.length(); i++) {
                        JSONObject env = envsData.getJSONObject(i);
                        String value = env.getString("value");
                        String id = env.getString("id");
                        String remarks = env.getString("remarks");
                        String[] cookies = value.split(";");
                        String USERID = null;

                        for (String cookie : cookies) {
                            String trimmedCookie = cookie.trim();
                            if (trimmedCookie.startsWith("USERID=")) {
                                int startIndex = "USERID=".length(); // 直接从 "USERID=" 的长度开始截取
                                USERID = trimmedCookie.substring(startIndex);
                                break;
                            }
                        }

                        if (USERID != null) {
                            String USERID1 = "USERID=" + USERID;
                            if (USERID1.equals(userid)) {
                             //   toast("正在提交账号");
                                String ck = cookie2 + ";" + userid + ";" + sid + ";";
                                JSONObject updateEnv = new JSONObject();
                                updateEnv.put("name", "elmck");
                                updateEnv.put("value", ck);
                                updateEnv.put("remarks", remarks);
                                updateEnv.put("id", id);
                                URL updateUrl = new URL(host + "/open/envs");
                                HttpURLConnection updateConnection = (HttpURLConnection) updateUrl.openConnection();
                                updateConnection.setRequestMethod("PUT");
                                updateConnection.setRequestProperty("Content-Type", "application/json");
                                updateConnection.setRequestProperty("Authorization", "Bearer " + token);
                                updateConnection.setDoOutput(true);
                                updateConnection.getOutputStream().write(updateEnv.toString().getBytes());
                                int updateResponseCode = updateConnection.getResponseCode();
                                if (updateResponseCode == HttpURLConnection.HTTP_OK) {
                                    // 变量更新成功
                                    toast("提交账号成功");
                                    return "Done";
                                } else {
                                    // 变量更新失败
                                    toast("提交账号失败" + updateResponseCode);
                                    return "Error";
                                }
                            }
                        }

                        if (i == envsData.length() - 1) {
                         //   toast("提交新账号");
                            JSONObject jsonObject = new JSONObject();
                            jsonObject.put("name", "elmck");
                            jsonObject.put("value", cookie2 + ";" + userid + ";" + sid + ";");
                            jsonObject.put("remarks", "");
                            JSONArray jsonArray = new JSONArray();
                            jsonArray.put(jsonObject);
                            URL url1 = new URL(host + "/open/envs");
                            HttpURLConnection connection = (HttpURLConnection) url1.openConnection();
                            connection.setRequestMethod("POST");
                            connection.setRequestProperty("Content-Type", "application/json");
                            connection.setRequestProperty("Authorization", "Bearer " + token);
                            connection.setDoOutput(true);
                            // 写入 JSON 数据
                            DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
                            outputStream.writeBytes(jsonArray.toString());
                            outputStream.flush();
                            outputStream.close();
                            int responseCode1 = connection.getResponseCode();
                            if (responseCode1 == HttpURLConnection.HTTP_OK) {
                                toast("提交新账号成功");
                                return "Done";
                            } else {
                                toast("提交新账号失败");
                                return "Error";
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return "Done";
        }
    }
}



