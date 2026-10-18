package ru.playsoftware.j2meloader.trainer;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.R;

/**
 * Floating memory trainer, similar to the classic "八门神器" workflow:
 * search a value, refine the matches after it changes, then overwrite it.
 */
public class TrainerService extends Service implements View.OnClickListener,
		AdapterView.OnItemClickListener {

	private static final String TAG = "TrainerService";
	private static final String ACTION_STOP = "ru.playsoftware.j2meloader.trainer.STOP";
	private static final int NOTIFICATION_ID = 0x7A11;
	private static final String CHANNEL_ID = "trainer";
	private static final int MAX_ROWS = 300;

	private WindowManager windowManager;
	private WindowManager.LayoutParams params;
	private View root;
	private View panel;
	private View ball;
	private TextView statusView;
	private EditText valueInput;
	private EditText newValueInput;
	private Button searchButton;
	private Button refineButton;
	private Button resetButton;
	private Button writeButton;
	private Button lockButton;
	private Button unlockButton;
	private Spinner typeSpinner;
	private ListView listView;
	private ArrayAdapter<String> adapter;

	private final List<Long> results = new ArrayList<>();
	private int type = MemoryTrainer.TYPE_DWORD;
	private Thread worker;
	private volatile boolean cancelled;
	private boolean focusable;
	private boolean collapsed;

	private final List<Long> locked = new ArrayList<>();
	private byte[] lockedValue;
	private int lockedType;
	private final Handler handler = new Handler(Looper.getMainLooper());

	public static void start(Context context) {
		Intent intent = new Intent(context, TrainerService.class);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			context.startForegroundService(intent);
		} else {
			context.startService(intent);
		}
	}

	public static void stop(Context context) {
		context.stopService(new Intent(context, TrainerService.class));
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		startForegroundCompat();
		windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
		showOverlay();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && ACTION_STOP.equals(intent.getAction())) {
			stopSelf();
			return START_NOT_STICKY;
		}
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		cancelled = true;
		handler.removeCallbacksAndMessages(null);
		if (root != null) {
			try {
				windowManager.removeView(root);
			} catch (Exception ignored) {
			}
			root = null;
		}
		super.onDestroy();
	}

	private void startForegroundCompat() {
		NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
			NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
					getString(R.string.trainer_title), NotificationManager.IMPORTANCE_LOW);
			channel.setShowBadge(false);
			nm.createNotificationChannel(channel);
		}
		PendingIntent stopIntent = PendingIntent.getService(this, 1,
				new Intent(this, TrainerService.class).setAction(ACTION_STOP),
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
		Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
				? new Notification.Builder(this, CHANNEL_ID)
				: new Notification.Builder(this);
		Notification notification = builder
				.setSmallIcon(R.mipmap.ic_launcher)
				.setContentTitle(getString(R.string.trainer_title))
				.setContentText(getString(R.string.trainer_running))
				.setOngoing(true)
				.addAction(android.R.drawable.ic_menu_close_clear_cancel,
						getString(R.string.trainer_close), stopIntent)
				.build();
		if (Build.VERSION.SDK_INT >= 34) {
			// the specialUse type is only known to Android 14 and newer
			startForeground(NOTIFICATION_ID, notification,
					ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
		} else {
			startForeground(NOTIFICATION_ID, notification);
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	private void showOverlay() {
		int windowType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
				? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
				: WindowManager.LayoutParams.TYPE_PHONE;
		params = new WindowManager.LayoutParams(
				WindowManager.LayoutParams.WRAP_CONTENT,
				WindowManager.LayoutParams.WRAP_CONTENT,
				windowType,
				WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
						| WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
						| WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
				PixelFormat.TRANSLUCENT);
		params.gravity = Gravity.TOP | Gravity.START;
		params.x = 0;
		params.y = 120;

		Context themed = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault);
		root = LayoutInflater.from(themed).inflate(R.layout.trainer_overlay, null);
		panel = root.findViewById(R.id.trainer_panel);
		ball = root.findViewById(R.id.trainer_ball);
		statusView = root.findViewById(R.id.trainer_status);
		valueInput = root.findViewById(R.id.trainer_value);
		newValueInput = root.findViewById(R.id.trainer_new_value);
		listView = root.findViewById(R.id.trainer_list);
		typeSpinner = root.findViewById(R.id.trainer_type);
		searchButton = root.findViewById(R.id.trainer_search);
		refineButton = root.findViewById(R.id.trainer_refine);
		resetButton = root.findViewById(R.id.trainer_reset);
		writeButton = root.findViewById(R.id.trainer_write);
		lockButton = root.findViewById(R.id.trainer_lock);
		unlockButton = root.findViewById(R.id.trainer_unlock);

		ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(themed,
				android.R.layout.simple_spinner_item, MemoryTrainer.TYPE_LABELS);
		typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		typeSpinner.setAdapter(typeAdapter);

		adapter = new ArrayAdapter<>(themed, android.R.layout.simple_list_item_1);
		listView.setAdapter(adapter);
		listView.setOnItemClickListener(this);

		searchButton.setOnClickListener(this);
		refineButton.setOnClickListener(this);
		resetButton.setOnClickListener(this);
		writeButton.setOnClickListener(this);
		lockButton.setOnClickListener(this);
		unlockButton.setOnClickListener(this);
		root.findViewById(R.id.trainer_close).setOnClickListener(this);
		root.findViewById(R.id.trainer_collapse).setOnClickListener(this);
		ball.setOnClickListener(this);

		View.OnClickListener focusListener = v -> setWindowFocusable(true);
		valueInput.setOnClickListener(focusListener);
		newValueInput.setOnClickListener(focusListener);
		valueInput.setOnFocusChangeListener((v, hasFocus) -> {
			if (hasFocus) setWindowFocusable(true);
		});

		View.OnTouchListener dragListener = new View.OnTouchListener() {
			private int startX;
			private int startY;
			private float touchX;
			private float touchY;
			private boolean moved;

			@Override
			public boolean onTouch(View v, MotionEvent event) {
				switch (event.getActionMasked()) {
					case MotionEvent.ACTION_DOWN:
						startX = params.x;
						startY = params.y;
						touchX = event.getRawX();
						touchY = event.getRawY();
						moved = false;
						return true;
					case MotionEvent.ACTION_MOVE: {
						int dx = (int) (event.getRawX() - touchX);
						int dy = (int) (event.getRawY() - touchY);
						if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
							moved = true;
						}
						if (moved) {
							params.x = startX + dx;
							params.y = startY + dy;
							windowManager.updateViewLayout(root, params);
						}
						return true;
					}
					case MotionEvent.ACTION_UP:
						// the touch listener consumes the event, so the click is fired here
						if (!moved) {
							v.performClick();
						}
						return true;
					default:
						return false;
				}
			}
		};
		root.findViewById(R.id.trainer_header).setOnTouchListener(dragListener);
		ball.setOnTouchListener(dragListener);

		try {
			windowManager.addView(root, params);
		} catch (Exception e) {
			Log.e(TAG, "can not add overlay", e);
			Toast.makeText(this, R.string.trainer_no_permission, Toast.LENGTH_LONG).show();
			stopSelf();
		}
	}

	private void setWindowFocusable(boolean focusable) {
		if (this.focusable == focusable || root == null) return;
		this.focusable = focusable;
		if (focusable) {
			params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
		} else {
			params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
			InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
			if (imm != null) {
				imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
			}
		}
		try {
			windowManager.updateViewLayout(root, params);
		} catch (Exception ignored) {
		}
	}

	@Override
	public void onClick(View v) {
		int id = v.getId();
		if (id == R.id.trainer_search) {
			setWindowFocusable(false);
			startSearch(false);
		} else if (id == R.id.trainer_refine) {
			setWindowFocusable(false);
			startSearch(true);
		} else if (id == R.id.trainer_reset) {
			results.clear();
			locked.clear();
			lockedValue = null;
			updateResultList();
			setStatus(getString(R.string.trainer_no_result));
		} else if (id == R.id.trainer_write) {
			setWindowFocusable(false);
			writeValues();
		} else if (id == R.id.trainer_lock) {
			startLock();
		} else if (id == R.id.trainer_unlock) {
			locked.clear();
			lockedValue = null;
			lockButton.setEnabled(writeButton.isEnabled());
			unlockButton.setEnabled(false);
			setStatus(getString(R.string.trainer_unlocked));
		} else if (id == R.id.trainer_close) {
			stopSelf();
		} else if (id == R.id.trainer_collapse) {
			setCollapsed(true);
		} else if (id == R.id.trainer_ball) {
			setCollapsed(false);
		}
	}

	private void setCollapsed(boolean collapse) {
		collapsed = collapse;
		panel.setVisibility(collapse ? View.GONE : View.VISIBLE);
		ball.setVisibility(collapse ? View.VISIBLE : View.GONE);
		setWindowFocusable(false);
		try {
			windowManager.updateViewLayout(root, params);
		} catch (Exception ignored) {
		}
	}

	@Override
	public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
		if (position < 0 || position >= results.size()) return;
		String value = MemoryTrainer.readValue(results.get(position), type);
		if (value != null) {
			newValueInput.setText(value);
		}
	}

	private void startSearch(boolean refine) {
		if (worker != null && worker.isAlive()) {
			Toast.makeText(this, R.string.trainer_busy, Toast.LENGTH_SHORT).show();
			return;
		}
		type = typeSpinner.getSelectedItemPosition();
		final String text = valueInput.getText().toString();
		byte[] pattern;
		try {
			pattern = MemoryTrainer.pattern(type, text);
		} catch (NumberFormatException e) {
			setStatus(getString(R.string.trainer_bad_value));
			return;
		}
		if (refine && results.isEmpty()) {
			setStatus(getString(R.string.trainer_no_result));
			return;
		}
		setBusy(true);
		setStatus(getString(R.string.trainer_searching));
		cancelled = false;
		final boolean doRefine = refine;
		worker = new Thread(() -> {
			List<Long> found;
			if (doRefine) {
				found = MemoryTrainer.refine(type, pattern, new ArrayList<>(results));
			} else {
				found = MemoryTrainer.search(type, pattern, new MemoryTrainer.Listener() {
					private long lastUpdate;

					@Override
					public void onProgress(long scanned, long total) {
						long now = System.currentTimeMillis();
						if (now - lastUpdate < 250) return;
						lastUpdate = now;
						String progress = getString(R.string.trainer_scanning)
								+ " " + (scanned >> 20) + "/" + (total >> 20) + " MB";
						handler.post(() -> setStatus(progress));
					}

					@Override
					public boolean isCancelled() {
						return cancelled || Thread.currentThread().isInterrupted();
					}
				});
			}
			handler.post(() -> {
				results.clear();
				results.addAll(found);
				setBusy(false);
				updateResultList();
			});
		}, "memory-search");
		worker.start();
	}

	private void writeValues() {
		if (results.isEmpty()) return;
		type = typeSpinner.getSelectedItemPosition();
		final byte[] pattern;
		try {
			pattern = MemoryTrainer.pattern(type, newValueInput.getText().toString());
		} catch (NumberFormatException e) {
			setStatus(getString(R.string.trainer_bad_value));
			return;
		}
		final List<Long> targets = new ArrayList<>();
		int selected = listView.getCheckedItemPosition();
		if (selected >= 0 && selected < results.size()) {
			targets.add(results.get(selected));
		} else {
			targets.addAll(results.size() > 20000 ? results.subList(0, 20000) : results);
		}
		setStatus(getString(R.string.trainer_writing));
		new Thread(() -> {
			int ok = 0;
			for (Long address : targets) {
				if (MemoryTrainer.writeValue(address, type, pattern)) {
					ok++;
				}
			}
			final int written = ok;
			handler.post(() -> {
				setStatus(getString(R.string.trainer_written, written, targets.size()));
				newValueInput.setText("");
				refreshValues();
			});
		}, "memory-write").start();
	}

	private void startLock() {
		if (results.isEmpty()) return;
		type = typeSpinner.getSelectedItemPosition();
		byte[] pattern;
		try {
			pattern = MemoryTrainer.pattern(type, newValueInput.getText().toString());
		} catch (NumberFormatException e) {
			setStatus(getString(R.string.trainer_bad_value));
			return;
		}
		locked.clear();
		int selected = listView.getCheckedItemPosition();
		if (selected >= 0 && selected < results.size()) {
			locked.add(results.get(selected));
		} else {
			locked.addAll(results.size() > 2000 ? results.subList(0, 2000) : results);
		}
		lockedValue = pattern;
		lockedType = type;
		unlockButton.setEnabled(true);
		setStatus(getString(R.string.trainer_locking, locked.size()));
		handler.removeCallbacks(lockTask);
		handler.post(lockTask);
	}

	private final Runnable lockTask = new Runnable() {
		@Override
		public void run() {
			if (lockedValue != null && !locked.isEmpty()) {
				for (Long address : locked) {
					MemoryTrainer.writeValue(address, lockedType, lockedValue);
				}
				handler.postDelayed(this, 300);
			}
		}
	};

	private void setBusy(boolean busy) {
		searchButton.setEnabled(!busy);
		refineButton.setEnabled(!busy && !results.isEmpty());
		resetButton.setEnabled(!busy && !results.isEmpty());
		writeButton.setEnabled(!busy && !results.isEmpty());
		lockButton.setEnabled(!busy && !results.isEmpty());
	}

	private void updateResultList() {
		adapter.clear();
		int rows = Math.min(results.size(), MAX_ROWS);
		for (int i = 0; i < rows; i++) {
			long address = results.get(i);
			String value = MemoryTrainer.readValue(address, type);
			adapter.add("0x" + Long.toHexString(address) + "   " + value);
		}
		adapter.notifyDataSetChanged();
		if (results.isEmpty()) {
			setStatus(getString(R.string.trainer_no_result));
		} else if (results.size() > MAX_ROWS) {
			setStatus(getString(R.string.trainer_result_more, results.size(), MAX_ROWS));
		} else {
			setStatus(getString(R.string.trainer_result, results.size()));
		}
		setBusy(false);
	}

	private void refreshValues() {
		if (results.isEmpty()) return;
		adapter.clear();
		int rows = Math.min(results.size(), MAX_ROWS);
		for (int i = 0; i < rows; i++) {
			long address = results.get(i);
			String value = MemoryTrainer.readValue(address, type);
			adapter.add("0x" + Long.toHexString(address) + "   " + value);
		}
		adapter.notifyDataSetChanged();
	}

	private void setStatus(String text) {
		if (statusView != null && !TextUtils.isEmpty(text)) {
			statusView.setText(text);
		}
	}

	@Override
	public void onTaskRemoved(Intent rootIntent) {
		stopSelf();
		super.onTaskRemoved(rootIntent);
	}
}
