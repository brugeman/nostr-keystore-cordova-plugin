package com.nostr.band.keyStore;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.security.KeyPairGeneratorSpec;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.R;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.spongycastle.util.encoders.Hex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.security.auth.x500.X500Principal;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import kotlin.Triple;

public class Nostr extends CordovaPlugin {
  private static final String CURRENT_ALIAS = "currentAlias";
  private static final String KEYS_ALIAS = "nostrKeys";
  private static final String KEYSTORE_PROVIDER_1 = "AndroidKeyStore";
  private static final String KEYSTORE_PROVIDER_2 = "AndroidKeyStoreBCWorkaround";
  private static final String KEYSTORE_PROVIDER_3 = "AndroidOpenSSL";
  private static final String RSA_ALGORITHM = "RSA/ECB/PKCS1Padding";
  private static final String TAG = "NostrLogTag";

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {

    if (action.equals("getPublicKey")) {

      return getPublicKey(callbackContext);

    } else if (action.equals("signEvent")) {

      return signEvent(args, callbackContext);

    } else if (action.equals("listKeys")) {

      return listKeys(callbackContext);

    } else if (action.equals("addKey")) {

      return addKey(callbackContext);

    } else if (action.equals("selectKey")) {

      return selectKey(args, callbackContext);

    } else if (action.equals("editKey")) {

      return editKey(args, callbackContext);

    } else if (action.equals("showKey")) {

      return showKey(args, callbackContext);

    } else if (action.equals("deleteKey")) {

      return deleteKey(args, callbackContext);

    }

    return false;
  }

  private boolean getPublicKey(CallbackContext callbackContext) throws JSONException {

    String currentAlias = getCurrentAlias();

    String privateKey = (Objects.nonNull(currentAlias) && !"".equals(currentAlias)) ? getPrivateKey(currentAlias) : "";

    Log.i(TAG, "privateKey " + privateKey);

    if ("".equals(privateKey)) {

      prompt("Please enter your private key", "Private key", Arrays.asList("cancel", "save"), callbackContext);

      return true;
    }

    String publicKey = new String(generatePublicKey(privateKey), StandardCharsets.UTF_8);
    Log.i(TAG, "publicKey " + publicKey);

    callbackContext.success(initResponseJSONObject(publicKey));

    return true;
  }

  private boolean signEvent(JSONArray args, CallbackContext callbackContext) throws JSONException {

    String currentAlias = getCurrentAlias();
    String privateKey = getPrivateKey(currentAlias);
    byte[] publicKey = Utils.pubkeyCreate(getBytePrivateKey(privateKey));
    JSONObject jsonObject = args.getJSONObject(0);
    int kind = jsonObject.getInt("kind");
    String content = jsonObject.getString("content");
    List<List<String>> tags = parseTags(jsonObject.getJSONArray("tags"));
    long createdAt = jsonObject.getLong("created_at");
    byte[] bytes = Utils.generateId(publicKey, createdAt, kind, tags, content);

    byte[] sign = Utils.sign(bytes, getBytePrivateKey(privateKey));
    String id = new String(Hex.encode(bytes), StandardCharsets.UTF_8);
    String signString = new String(Hex.encode(sign), StandardCharsets.UTF_8);
    String publicKeyString = new String(generatePublicKey(privateKey), StandardCharsets.UTF_8);

    jsonObject.put("id", id);
    jsonObject.put("pubkey", publicKeyString);
    jsonObject.put("sig", signString);

    callbackContext.success(jsonObject);

    return true;
  }

  private boolean listKeys(CallbackContext callbackContext) throws JSONException {
    String keysData = getKeysStringData();
    JSONObject keysObjectData = getKeysObjectData(keysData);

    callbackContext.success(keysObjectData);

    return true;
  }

  private boolean addKey(CallbackContext callbackContext) {

    prompt("Please enter your private key", "Private key", Arrays.asList("cancel", "save"), callbackContext);

    return true;
  }

  private boolean selectKey(JSONArray args, CallbackContext callbackContext) throws JSONException {

    JSONObject jsonObject = args.getJSONObject(0);
    String publicKey = jsonObject.getString("publicKey");

    String keysData = getKeysStringData();
    JSONObject keysObjectData = getKeysObjectData(keysData);

    if (!existKey(publicKey, keysObjectData.names())) {
      callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "Key doesn't exist"));
      return false;
    }

    keysObjectData.put(CURRENT_ALIAS, publicKey);

    KeyStorage.writeValues(getContext(), KEYS_ALIAS, keysObjectData.toString().getBytes());

    callbackContext.success(keysObjectData);

    return true;
  }

  private boolean editKey(JSONArray args, CallbackContext callbackContext) throws JSONException {

    JSONObject jsonObject = args.getJSONObject(0);
    String publicKey = jsonObject.getString("publicKey");
    String name = jsonObject.getString("name");

    String keysData = getKeysStringData();
    JSONObject keysObjectData = getKeysObjectData(keysData);

    if (!existKey(publicKey, keysObjectData.names())) {
      callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "Key doesn't exist"));
      return false;
    }
    if (existKeyName(publicKey, name, keysObjectData)) {
      callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "Name already exist"));
      return false;
    }

    JSONObject key = keysObjectData.getJSONObject(publicKey);
    key.put("name", name);

    KeyStorage.writeValues(getContext(), KEYS_ALIAS, keysObjectData.toString().getBytes());

    callbackContext.success(keysObjectData);

    return true;
  }

  private boolean showKey(JSONArray args, CallbackContext callbackContext) throws JSONException {

    JSONObject jsonObject = args.getJSONObject(0);
    String publicKey = jsonObject.getString("publicKey");

    String privateKey = getPrivateKey(publicKey);

    if ("".equals(privateKey)) {
      callbackContext.error("Key doesn't exist");
      return false;
    }


    Runnable runnable = () -> {
      AlertDialog.Builder alertDialog = initAlertDialog1(privateKey, "Private Key");
      setOkButton(alertDialog, "ok", callbackContext);
      setOnOkListener(alertDialog, callbackContext);
      changeTextDirection(alertDialog);
    };

    this.cordova.getActivity().runOnUiThread(runnable);

    return true;
  }

  private boolean deleteKey(JSONArray args, CallbackContext callbackContext) throws JSONException {

    JSONObject jsonObject = args.getJSONObject(0);
    String publicKey = jsonObject.getString("publicKey");

    String keysData = getKeysStringData();
    JSONObject keysObjectData = getKeysObjectData(keysData);

    if (!existKey(publicKey, keysObjectData.names())) {
      callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "Key doesn't exist"));
      return false;
    }

    Runnable runnable = () -> {
      AlertDialog.Builder alertDialog = initAlertDialog("", "Do you want delete key?");
      setPositiveDeleteButton(alertDialog, "ok", keysObjectData, publicKey, callbackContext);
      setOkButton(alertDialog, "cancel", callbackContext);
      setOnOkListener(alertDialog, callbackContext);
      changeTextDirection(alertDialog);
    };

    this.cordova.getActivity().runOnUiThread(runnable);

    return true;
  }

  private List<List<String>> parseTags(JSONArray jsonArray) throws JSONException {
    List<List<String>> allTags = new ArrayList<>();
    for (int i = 0; i < jsonArray.length(); i++) {
      ArrayList<String> tags = new ArrayList<>();
      JSONArray tagsJsonArray = jsonArray.getJSONArray(i);
      for (int j = 0; j < tagsJsonArray.length(); j++) {
        tags.add(tagsJsonArray.getString(j));
      }
      allTags.add(tags);
    }
    return allTags;
  }

  private void saveCurrentAlias(JSONObject keysObjectData, String keyName, String publicKey) throws JSONException {
    addKey(keysObjectData, publicKey, keyName);
    KeyStorage.writeValues(getContext(), KEYS_ALIAS, keysObjectData.toString().getBytes());
  }

  private boolean existKey(String publicKey, JSONArray names) throws JSONException {

    Set<String> namesList = mapJSONArrayToSet(names);

    if (!namesList.contains(publicKey)) {
      return false;
    }
    return true;
  }

  private boolean existKeyName(String publicKey, String name, JSONObject keysObjectData) throws JSONException {

    Set<String> namesList = mapJSONArrayToSet(keysObjectData.names());

    Set<String> namesSet = namesList.stream()
            .filter(keyName -> !CURRENT_ALIAS.equals(keyName) && !publicKey.equals(keyName))
            .map(keyName -> {
              try {
                JSONObject key = keysObjectData.getJSONObject(keyName);
                return key.getString("name");
              } catch (JSONException e) {
                return null;
              }
            })
            .collect(Collectors.toSet());

    if (namesSet.contains(name)) {
      return true;
    }
    return false;
  }


  private Set<String> mapJSONArrayToSet(JSONArray names) throws JSONException {
    Set<String> namesList = new HashSet<>();

    if (names != null && names.length() > 0) {
      for (int i = 0; i < names.length(); i++) {
        String name = names.getString(i);
        namesList.add(name);
      }
    }

    return namesList;
  }

  private void addKey(JSONObject keysObjectData, String publicKey, String keyName) throws JSONException {
    JSONArray names = keysObjectData.names();
    if (names != null && names.length() > 0) {
      for (int i = 0; i < names.length(); i++) {
        String name = names.getString(i);
        if (!name.equals(CURRENT_ALIAS)) {
          JSONObject jsonObject = keysObjectData.getJSONObject(name);
          jsonObject.put("isCurrent", false);
          keysObjectData.put(name, jsonObject);
        }
      }
    }

    keysObjectData.put(CURRENT_ALIAS, publicKey);

    JSONObject newKey = new JSONObject();
    newKey.put("name", keyName);
    newKey.put("publicKey", publicKey);
    newKey.put("isCurrent", true);

    keysObjectData.put(publicKey, newKey);
  }

  private String getCurrentAlias() {
    try {
      String keysData = getKeysStringData();
      JSONObject keysObjectData = getKeysObjectData(keysData);
      return keysObjectData.getString(CURRENT_ALIAS);
    } catch (JSONException e) {
      return "";
    }
  }

  private String getKeysStringData() {
    byte[] keys = KeyStorage.readValues(getContext(), KEYS_ALIAS);
    return new String(keys);
  }

  private JSONObject getKeysObjectData(String stringData) throws JSONException {
    if (stringData != null && !stringData.equals("")) {
      return new JSONObject(stringData);
    }
    return new JSONObject();
  }

  private void savePrivateKey(String alias, String input) {

    try {

      KeyStore keyStore = KeyStore.getInstance(getKeyStore());
      keyStore.load(null);

      if (!keyStore.containsAlias(alias)) {
        Calendar start = Calendar.getInstance();
        Calendar end = Calendar.getInstance();
        end.add(Calendar.YEAR, 1);
        KeyPairGeneratorSpec spec = new KeyPairGeneratorSpec.Builder(getContext()).setAlias(alias)
                .setSubject(new X500Principal("CN=" + alias)).setSerialNumber(BigInteger.ONE)
                .setStartDate(start.getTime()).setEndDate(end.getTime()).build();

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", getKeyStore());
        generator.initialize(spec);

        KeyPair keyPair = generator.generateKeyPair();

        Log.i(TAG, "created new key pairs");
      }

      PublicKey publicKey = keyStore.getCertificate(alias).getPublicKey();

      if (input.isEmpty()) {
        Log.d(TAG, "Exception: input text is empty");
        return;
      }

      Cipher cipher = Cipher.getInstance(RSA_ALGORITHM);
      cipher.init(Cipher.ENCRYPT_MODE, publicKey);
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      CipherOutputStream cipherOutputStream = new CipherOutputStream(outputStream, cipher);
      cipherOutputStream.write(input.getBytes(StandardCharsets.UTF_8));
      cipherOutputStream.close();
      byte[] vals = outputStream.toByteArray();

      KeyStorage.writeValues(getContext(), alias, vals);
      Log.i(TAG, "key created and stored successfully");

    } catch (Exception e) {
      Log.e(TAG, "Exception: " + e.getMessage());
    }

  }

  private String getPrivateKey(String alias) {
    try {
      KeyStore keyStore = KeyStore.getInstance(getKeyStore());
      keyStore.load(null);
      PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, null);

      Cipher output = Cipher.getInstance(RSA_ALGORITHM);
      output.init(Cipher.DECRYPT_MODE, privateKey);
      CipherInputStream cipherInputStream = new CipherInputStream(new ByteArrayInputStream(KeyStorage.readValues(getContext(), alias)), output);

      ArrayList<Byte> values = new ArrayList<>();
      int nextByte;
      while ((nextByte = cipherInputStream.read()) != -1) {
        values.add((byte) nextByte);
      }
      byte[] bytes = new byte[values.size()];
      for (int i = 0; i < bytes.length; i++) {
        bytes[i] = values.get(i);
      }

      return new String(bytes, 0, bytes.length, StandardCharsets.UTF_8);

    } catch (Exception e) {
      Log.e(TAG, "Exception: " + e.getMessage());
      return "";
    }
  }

  private Context getContext() {
    return cordova.getActivity().getApplicationContext();
  }

  private String getKeyStore() {
    try {
      KeyStore.getInstance(KEYSTORE_PROVIDER_1);
      return KEYSTORE_PROVIDER_1;
    } catch (Exception err) {
      try {
        KeyStore.getInstance(KEYSTORE_PROVIDER_2);
        return KEYSTORE_PROVIDER_2;
      } catch (Exception e) {
        return KEYSTORE_PROVIDER_3;
      }
    }
  }

  private synchronized void prompt(String message, String title, List<String> buttonLabels, final CallbackContext callbackContext) {

    Runnable runnable = () -> {

      AlertDialog.Builder alertDialog = initAlertDialog(message, title);
      final TextInputLayout namePromptInput = initInput("name");
      final TextInputLayout nsecPromptInput = initInput("nsec...");
      initInputs(alertDialog, namePromptInput, nsecPromptInput);

      setNegativeButton(alertDialog, buttonLabels.get(0), callbackContext);
      setPositiveButton(alertDialog, buttonLabels.get(1), namePromptInput, nsecPromptInput, callbackContext);
      setNeutralButton(alertDialog, "Generate nsec", nsecPromptInput, callbackContext);
      setOnCancelListener(alertDialog, callbackContext);
      changeTextDirection1(alertDialog, nsecPromptInput);
    };

    this.cordova.getActivity().runOnUiThread(runnable);
  }

  private void initInputs(AlertDialog.Builder alertDialog, TextInputLayout namePromptInput, TextInputLayout nsecPromptInput) {
    LinearLayout linearLayout = new LinearLayout(alertDialog.getContext());
    linearLayout.setOrientation(LinearLayout.VERTICAL);
    linearLayout.addView(namePromptInput);
    linearLayout.addView(nsecPromptInput);

    alertDialog.setView(linearLayout);
  }

  @SuppressLint("RestrictedApi")
  private TextInputLayout initInput(String defaultText) {

    TextInputLayout textInputLayout = new TextInputLayout(cordova.getActivity(), null, R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox);
    textInputLayout.setBoxStrokeColor(Color.BLACK);
    textInputLayout.setPadding(50, 0, 50, 0);

    TextInputEditText editText = new TextInputEditText(textInputLayout.getContext());
    editText.setBackgroundColor(Color.WHITE);

    editText.setTextColor(Color.BLACK);
    editText.setText(defaultText);
    editText.setPadding(50, editText.getPaddingTop(), editText.getPaddingRight(), editText.getPaddingBottom());

    textInputLayout.addView(editText);

    return textInputLayout;
  }

  private AlertDialog.Builder initAlertDialog(String message, String title) {
    AlertDialog.Builder alertDialog = createDialog(cordova);
    alertDialog.setMessage(message);
    alertDialog.setTitle(title);
    alertDialog.setCancelable(true);

    return alertDialog;
  }

  private AlertDialog.Builder initAlertDialog1(String message, String title) {
    AlertDialog.Builder alertDialog = createDialog(cordova);
    alertDialog.setMessage(message);
    alertDialog.setTitle(title);
    alertDialog.setCancelable(true);

    MultiFormatWriter multiFormatWriter = new MultiFormatWriter();
    try {
      BitMatrix bitMatrix = multiFormatWriter.encode(message, BarcodeFormat.QR_CODE, 500, 500);
      BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
      final Bitmap bitmap = barcodeEncoder.createBitmap(bitMatrix);
      BitmapDrawable bitmapDrawable = new BitmapDrawable(bitmap);
      alertDialog.setIcon(bitmapDrawable);
    } catch (WriterException e) {
      throw new RuntimeException(e);
    }
    return alertDialog;
  }

  private void setPositiveButton(AlertDialog.Builder alertDialog, String buttonLabel, TextInputLayout namePromptInput, TextInputLayout nsecPromptInput, CallbackContext callbackContext) {
    alertDialog.setPositiveButton(buttonLabel,
            (dialog, which) -> {
              dialog.dismiss();
              String privateKey = nsecPromptInput.getEditText().getText().toString();
              String keyName = namePromptInput.getEditText().getText().toString();
              String publicKey = new String(generatePublicKey(privateKey), StandardCharsets.UTF_8);

              try {
                String keysData = getKeysStringData();
                JSONObject keysObjectData = getKeysObjectData(keysData);
                if (!existKey(publicKey, keysObjectData.names())) {
                  callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "Key doesn't exist"));
                }
                if (existKeyName(publicKey, keyName, keysObjectData)) {
                  callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "Name already exist"));
                }
                saveCurrentAlias(keysObjectData, keyName, publicKey);
              } catch (JSONException e) {
                throw new RuntimeException(e);
              }

              savePrivateKey(publicKey, privateKey);

              JSONObject result = initResponseJSONObject(publicKey);
              callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, result));

            });
  }

  private void setNeutralButton(AlertDialog.Builder alertDialog, String buttonLabel, TextInputLayout nsecPromptInput, CallbackContext callbackContext) {
    alertDialog.setNeutralButton(buttonLabel,
            (dialog, which) -> {
            });
  }

  private void setPositiveDeleteButton(AlertDialog.Builder alertDialog, String buttonLabel, JSONObject keysObjectData, String publicKey, CallbackContext callbackContext) {
    alertDialog.setPositiveButton(buttonLabel,
            (dialog, which) -> {
              dialog.dismiss();

              keysObjectData.remove(publicKey);
              try {
                String currentKey = keysObjectData.getString(CURRENT_ALIAS);
                if (currentKey.equals(publicKey)) {
                  keysObjectData.put(CURRENT_ALIAS, "");
                }
              } catch (JSONException e) {
                throw new RuntimeException(e);
              }

              KeyStorage.removeValues(getContext(), publicKey);

              callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, keysObjectData));
            });
  }

  private void setNegativeButton(AlertDialog.Builder alertDialog, String buttonLabel, CallbackContext callbackContext) {
    alertDialog.setNegativeButton(buttonLabel,
            (dialog, which) -> {
              dialog.dismiss();
              callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR));
            });
  }

  private void setOkButton(AlertDialog.Builder alertDialog, String buttonLabel, CallbackContext callbackContext) {
    alertDialog.setNegativeButton(buttonLabel,
            (dialog, which) -> {
              dialog.dismiss();
              callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
            });
  }

  private void setOnOkListener(AlertDialog.Builder alertDialog, CallbackContext callbackContext) {
    alertDialog.setOnCancelListener(dialog -> {
      dialog.dismiss();
      callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
    });
  }

  private void setOnCancelListener(AlertDialog.Builder alertDialog, CallbackContext callbackContext) {
    alertDialog.setOnCancelListener(dialog -> {
      dialog.dismiss();
      callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR));
    });
  }

  private JSONObject initResponseJSONObject(String response) {
    final JSONObject result = new JSONObject();
    try {
      result.put("pubKey", response);
    } catch (JSONException e) {
      Log.i("response", response);
      Log.e("JSONException", e.getMessage());
    }

    return result;
  }

  @SuppressLint("NewApi")
  private AlertDialog.Builder createDialog(CordovaInterface cordova) {
    int currentApiVersion = android.os.Build.VERSION.SDK_INT;
    if (currentApiVersion >= android.os.Build.VERSION_CODES.HONEYCOMB) {
      return new AlertDialog.Builder(cordova.getActivity(), AlertDialog.THEME_DEVICE_DEFAULT_DARK);
    } else {
      return new AlertDialog.Builder(cordova.getActivity());
    }
  }

  @SuppressLint("NewApi")
  private void changeTextDirection(AlertDialog.Builder dlg) {
    int currentApiVersion = android.os.Build.VERSION.SDK_INT;
    dlg.create();
    AlertDialog dialog = dlg.show();
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {

    });

    if (currentApiVersion >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
      TextView messageView = dialog.findViewById(android.R.id.message);
      messageView.setTextDirection(android.view.View.TEXT_DIRECTION_LOCALE);
    }
  }

  @SuppressLint("NewApi")
  private void changeTextDirection1(AlertDialog.Builder dlg, TextInputLayout nsecPromptInput) {
    int currentApiVersion = android.os.Build.VERSION.SDK_INT;
    dlg.create();
    AlertDialog dialog = dlg.show();
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
      EditText editText = nsecPromptInput.getEditText();
      //todo implement logic for generating nsec
      editText.setText("nsec1560zpnjua2kuzas7qj7n7h0adxk4d8z4d59vxkattetq8ryway6q5v0psr");
    });

    if (currentApiVersion >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
      TextView messageView = dialog.findViewById(android.R.id.message);
      messageView.setTextDirection(android.view.View.TEXT_DIRECTION_LOCALE);
    }
  }

  private byte[] generatePublicKey(String privateKey) {
    byte[] bytes = Utils.pubkeyCreate(getBytePrivateKey(privateKey));
    return Hex.encode(bytes);
  }

  private byte[] getBytePrivateKey(String privateKey) {
    Triple<String, byte[], Bech32.Encoding> stringEncodingTriple = Bech32.decodeBytes(privateKey, false);
    return stringEncodingTriple.getSecond();
  }
}
