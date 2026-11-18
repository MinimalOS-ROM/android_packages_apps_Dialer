/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.dialer.main.impl;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog;
import android.support.v4.app.ActivityCompat;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.android.dialer.R;
import com.android.dialer.interactions.PhoneNumberInteraction.DisambigDialogDismissedListener;
import com.android.dialer.interactions.PhoneNumberInteraction.InteractionErrorCode;
import com.android.dialer.interactions.PhoneNumberInteraction.InteractionErrorListener;
import com.android.dialer.main.MainActivityPeer;
import com.android.dialer.main.impl.bottomnav.BottomNavBar.TabIndex;
import com.android.dialer.util.TransactionSafeActivity;
import java.util.ArrayList;
import java.util.List;

/** Minimal monochrome keypad-friendly MainActivity. */
public class MainActivity extends TransactionSafeActivity
    implements MainActivityPeer.PeerSupplier,
        InteractionErrorListener,
        DisambigDialogDismissedListener {

  private static final int PERMISSION_REQUEST_CODE = 1001;

  private static final String[][] DIALPAD_KEYS = {
    {"1", "OO"}, {"2", "ABC"}, {"3", "DEF"},
    {"4", "GHI"}, {"5", "JKL"}, {"6", "MNO"},
    {"7", "PQRS"}, {"8", "TUV"}, {"9", "WXYZ"},
    {"*", "+"}, {"0", "_"}, {"#", ""}
  };

  private static class CallLogEntry {
    final String name;
    final String number;
    final String type;
    final String time;

    CallLogEntry(String name, String number, String type, String time) {
      this.name = name;
      this.number = number;
      this.type = type;
      this.time = time;
    }
  }

  private ListView callLogList;
  private View dialpadContainer;
  private TextView digitsDisplay;
  private TextView headerMode;
  private TextView softkeyLeft;
  private TextView softkeyCenter;
  private TextView softkeyRight;

  private final List<CallLogEntry> callLogs = new ArrayList<>();
  private BaseAdapter callLogAdapter;
  private final StringBuilder digits = new StringBuilder();
  private boolean inDialpad = false;
  private int dialpadFocusIndex = 0;
  private int callLogFocusIndex = 0;
  private ToneGenerator toneGenerator;

  public static Intent getShowCallLogIntent(Context context) {
    return getShowTabIntent(context, TabIndex.CALL_LOG);
  }

  public static Intent getShowTabIntent(Context context, @TabIndex int tabIndex) {
    return new Intent(context, MainActivity.class);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (getSupportActionBar() != null) {
      getSupportActionBar().hide();
    }
    setContentView(R.layout.minimal_main);

    callLogList = findViewById(R.id.minimal_call_log_list);
    dialpadContainer = findViewById(R.id.minimal_dialpad_container);
    digitsDisplay = findViewById(R.id.minimal_digits_display);
    headerMode = findViewById(R.id.minimal_header_mode);
    softkeyLeft = findViewById(R.id.minimal_softkey_left);
    softkeyCenter = findViewById(R.id.minimal_softkey_center);
    softkeyRight = findViewById(R.id.minimal_softkey_right);

    try {
      toneGenerator = new ToneGenerator(AudioManager.STREAM_DTMF, 80);
    } catch (RuntimeException e) {
      toneGenerator = null;
    }

    setupCallLogList();
    setupDialpadGrid();
    setupSoftkeys();
    handleIntent(getIntent());
    loadCallLogs();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleIntent(intent);
  }

  @Override
  protected void onResume() {
    super.onResume();
    loadCallLogs();
  }

  @Override
  protected void onDestroy() {
    if (toneGenerator != null) {
      toneGenerator.release();
      toneGenerator = null;
    }
    super.onDestroy();
  }

  private void handleIntent(Intent intent) {
    if (intent != null && intent.getData() != null && "tel".equals(intent.getData().getScheme())) {
      String num = intent.getData().getSchemeSpecificPart();
      if (!TextUtils.isEmpty(num)) {
        digits.setLength(0);
        digits.append(num);
        showDialpad(true);
      }
    }
  }

  private void setupCallLogList() {
    callLogAdapter =
        new BaseAdapter() {
          @Override
          public int getCount() {
            return callLogs.size();
          }

          @Override
          public CallLogEntry getItem(int position) {
            return callLogs.get(position);
          }

          @Override
          public long getItemId(int position) {
            return position;
          }

          @Override
          public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
              v =
                  LayoutInflater.from(MainActivity.this)
                      .inflate(R.layout.minimal_call_log_item, parent, false);
            }
            CallLogEntry entry = getItem(position);
            boolean focused = (position == callLogFocusIndex);
            int textColor = focused ? 0xFF000000 : 0xFFFFFFFF;
            v.setBackgroundColor(focused ? 0xFFFFFFFF : 0xFF000000);
            TextView nameView = v.findViewById(R.id.minimal_log_name);
            TextView typeView = v.findViewById(R.id.minimal_log_type);
            TextView timeView = v.findViewById(R.id.minimal_log_time);
            nameView.setText(entry.name);
            nameView.setTextColor(textColor);
            typeView.setText(entry.type);
            typeView.setTextColor(textColor);
            timeView.setText(entry.time);
            timeView.setTextColor(textColor);
            return v;
          }
        };
    callLogList.setAdapter(callLogAdapter);
    callLogList.setOnItemClickListener(
        (parent, view, position, id) -> placeCall(callLogs.get(position).number));
  }

  private void setupDialpadGrid() {
    GridLayout grid = findViewById(R.id.minimal_dialpad_grid);
    for (int i = 0; i < DIALPAD_KEYS.length; i++) {
      final String num = DIALPAD_KEYS[i][0];
      final String sub = DIALPAD_KEYS[i][1];

      LinearLayout cell = new LinearLayout(this);
      cell.setOrientation(LinearLayout.VERTICAL);
      cell.setGravity(Gravity.CENTER);
      cell.setFocusable(true);
      cell.setFocusableInTouchMode(true);
      cell.setClickable(true);
      cell.setBackgroundResource(R.drawable.monochrome_focus_selector);

      GridLayout.LayoutParams params =
          new GridLayout.LayoutParams(
              GridLayout.spec(i / 3, 1f), GridLayout.spec(i % 3, 1f));
      params.width = 0;
      params.height = 0;
      cell.setLayoutParams(params);

      TextView numView = new TextView(this);
      numView.setText(num);
      numView.setTextSize(24);
      numView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
      numView.setTextColor(getResources().getColorStateList(R.color.monochrome_text_selector, null));
      numView.setDuplicateParentStateEnabled(true);
      numView.setGravity(Gravity.CENTER);

      TextView subView = new TextView(this);
      subView.setText(sub);
      subView.setTextSize(10);
      subView.setTypeface(Typeface.MONOSPACE);
      subView.setTextColor(getResources().getColorStateList(R.color.monochrome_text_selector, null));
      subView.setDuplicateParentStateEnabled(true);
      subView.setGravity(Gravity.CENTER);

      cell.addView(numView);
      cell.addView(subView);
      cell.setOnClickListener(v -> appendDigit(num));
      grid.addView(cell);
    }
  }

  private void setupSoftkeys() {
    softkeyLeft.setOnClickListener(
        v -> {
          if (inDialpad) {
            placeCall(digits.toString());
          } else {
            int pos = callLogList.getSelectedItemPosition();
            if (pos >= 0 && pos < callLogs.size()) {
              placeCall(callLogs.get(pos).number);
            }
          }
        });

    softkeyCenter.setOnClickListener(
        v -> {
          if (inDialpad) {
            deleteDigit();
          } else {
            showDialpad(true);
          }
        });

    softkeyRight.setOnClickListener(
        v -> {
          if (inDialpad) {
            showDialpad(false);
          } else {
            finish();
          }
        });
  }

  private void showDialpad(boolean show) {
    inDialpad = show;
    dialpadContainer.setVisibility(show ? View.VISIBLE : View.GONE);
    callLogList.setVisibility(show ? View.GONE : View.VISIBLE);
    headerMode.setText(show ? "DIALPAD" : "CALL LOG");
    softkeyCenter.setText(show ? "CLEAR" : "DIALPAD");
    softkeyRight.setText(show ? "BACK" : "EXIT");
    updateDigitsDisplay();
    if (show) {
      dialpadFocusIndex = 0;
      updateDialpadFocus();
    } else {
      callLogList.requestFocus();
    }
  }

  private void updateDialpadFocus() {
    GridLayout grid = findViewById(R.id.minimal_dialpad_grid);
    if (grid == null) {
      return;
    }
    for (int i = 0; i < grid.getChildCount(); i++) {
      View cell = grid.getChildAt(i);
      boolean focused = (i == dialpadFocusIndex);
      cell.setSelected(focused);
      if (focused) {
        cell.requestFocus();
      }
      if (cell instanceof ViewGroup) {
        ViewGroup vg = (ViewGroup) cell;
        for (int j = 0; j < vg.getChildCount(); j++) {
          View child = vg.getChildAt(j);
          if (child instanceof TextView) {
            ((TextView) child).setTextColor(focused ? 0xFF000000 : 0xFFFFFFFF);
          }
        }
      }
      cell.setBackgroundColor(focused ? 0xFFFFFFFF : 0xFF000000);
    }
  }

  private void appendDigit(String d) {
    digits.append(d);
    updateDigitsDisplay();
    playShortTone(d);
  }

  private void deleteDigit() {
    if (digits.length() > 0) {
      digits.deleteCharAt(digits.length() - 1);
      updateDigitsDisplay();
    }
  }

  private void updateDigitsDisplay() {
    digitsDisplay.setText(digits.length() > 0 ? digits.toString() : "_");
  }

  private void playShortTone(String d) {
    if (toneGenerator == null) {
      return;
    }
    int tone = -1;
    switch (d) {
      case "0": tone = ToneGenerator.TONE_DTMF_0; break;
      case "1": tone = ToneGenerator.TONE_DTMF_1; break;
      case "2": tone = ToneGenerator.TONE_DTMF_2; break;
      case "3": tone = ToneGenerator.TONE_DTMF_3; break;
      case "4": tone = ToneGenerator.TONE_DTMF_4; break;
      case "5": tone = ToneGenerator.TONE_DTMF_5; break;
      case "6": tone = ToneGenerator.TONE_DTMF_6; break;
      case "7": tone = ToneGenerator.TONE_DTMF_7; break;
      case "8": tone = ToneGenerator.TONE_DTMF_8; break;
      case "9": tone = ToneGenerator.TONE_DTMF_9; break;
      case "*": tone = ToneGenerator.TONE_DTMF_S; break;
      case "#": tone = ToneGenerator.TONE_DTMF_P; break;
      default: break;
    }
    if (tone != -1) {
      toneGenerator.startTone(tone, 100);
    }
  }

  private void placeCall(String number) {
    if (TextUtils.isEmpty(number)) {
      return;
    }
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
        != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(
          this, new String[] {Manifest.permission.CALL_PHONE}, PERMISSION_REQUEST_CODE);
      return;
    }
    TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
    if (tm != null) {
      tm.placeCall(Uri.fromParts("tel", number, null), null);
    }
  }

  private void loadCallLogs() {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
        != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(
          this,
          new String[] {Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE},
          PERMISSION_REQUEST_CODE);
      return;
    }
    callLogs.clear();
    String[] projection = {
      CallLog.Calls.CACHED_NAME,
      CallLog.Calls.NUMBER,
      CallLog.Calls.TYPE,
      CallLog.Calls.DATE
    };
    try (Cursor cursor =
        getContentResolver()
            .query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC LIMIT 50")) {
      if (cursor != null) {
        while (cursor.moveToNext()) {
          String name = cursor.getString(0);
          String number = cursor.getString(1);
          int typeInt = cursor.getInt(2);
          long date = cursor.getLong(3);

          String displayName = !TextUtils.isEmpty(name) ? name : (!TextUtils.isEmpty(number) ? number : "Unknown");
          String typeStr = "CALL";
          if (typeInt == CallLog.Calls.INCOMING_TYPE) typeStr = "INCOMING";
          else if (typeInt == CallLog.Calls.OUTGOING_TYPE) typeStr = "OUTGOING";
          else if (typeInt == CallLog.Calls.MISSED_TYPE) typeStr = "MISSED";

          String timeStr =
              DateUtils.getRelativeTimeSpanString(
                      date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                  .toString();
          callLogs.add(new CallLogEntry(displayName, number, typeStr, timeStr));
        }
      }
    }
    callLogAdapter.notifyDataSetChanged();
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == PERMISSION_REQUEST_CODE) {
      loadCallLogs();
    }
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    if (inDialpad && event.getAction() == KeyEvent.ACTION_DOWN) {
      int keyCode = event.getKeyCode();
      if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
        dialpadFocusIndex = (dialpadFocusIndex + 1) % 12;
        updateDialpadFocus();
        return true;
      }
      if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
        dialpadFocusIndex = (dialpadFocusIndex + 11) % 12;
        updateDialpadFocus();
        return true;
      }
      if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
        dialpadFocusIndex = (dialpadFocusIndex + 3) % 12;
        updateDialpadFocus();
        return true;
      }
      if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
        dialpadFocusIndex = (dialpadFocusIndex + 9) % 12;
        updateDialpadFocus();
        return true;
      }
      if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
        appendDigit(DIALPAD_KEYS[dialpadFocusIndex][0]);
        return true;
      }
    } else if (!inDialpad && event.getAction() == KeyEvent.ACTION_DOWN) {
      int keyCode = event.getKeyCode();
      if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !callLogs.isEmpty()) {
        callLogFocusIndex = (callLogFocusIndex + 1) % callLogs.size();
        callLogList.smoothScrollToPosition(callLogFocusIndex);
        callLogAdapter.notifyDataSetChanged();
        return true;
      }
      if (keyCode == KeyEvent.KEYCODE_DPAD_UP && !callLogs.isEmpty()) {
        callLogFocusIndex = (callLogFocusIndex - 1 + callLogs.size()) % callLogs.size();
        callLogList.smoothScrollToPosition(callLogFocusIndex);
        callLogAdapter.notifyDataSetChanged();
        return true;
      }
      if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
          && callLogFocusIndex >= 0
          && callLogFocusIndex < callLogs.size()) {
        placeCall(callLogs.get(callLogFocusIndex).number);
        return true;
      }
    }
    return super.dispatchKeyEvent(event);
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
      if (!inDialpad) {
        showDialpad(true);
      }
      appendDigit(String.valueOf(keyCode - KeyEvent.KEYCODE_0));
      return true;
    }
    if (keyCode == KeyEvent.KEYCODE_STAR) {
      if (!inDialpad) {
        showDialpad(true);
      }
      appendDigit("*");
      return true;
    }
    if (keyCode == KeyEvent.KEYCODE_POUND) {
      if (!inDialpad) {
        showDialpad(true);
      }
      appendDigit("#");
      return true;
    }
    if (keyCode == KeyEvent.KEYCODE_DEL) {
      if (inDialpad) {
        deleteDigit();
        return true;
      }
    }
    if (keyCode == KeyEvent.KEYCODE_CALL) {
      if (inDialpad) {
        placeCall(digits.toString());
      } else {
        int pos = callLogList.getSelectedItemPosition();
        if (pos >= 0 && pos < callLogs.size()) {
          placeCall(callLogs.get(pos).number);
        }
      }
      return true;
    }
    if (keyCode == KeyEvent.KEYCODE_BACK && inDialpad) {
      showDialpad(false);
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  public void interactionError(@InteractionErrorCode int interactionErrorCode) {}

  @Override
  public void onDisambigDialogDismissed() {}

  @Override
  public MainActivityPeer getPeer() {
    return null;
  }
}
