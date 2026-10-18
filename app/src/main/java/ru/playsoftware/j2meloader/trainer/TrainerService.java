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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.playsoftware.j2meloader.R;

/**
 * Floating memory trainer, similar to the classic "八门神器" workflow:
 * search a value, refine the matches after it changes, then overwrite it.
 * <p>
 * The search button doubles as the refine button: once there are results, the same
 * value field is used to enter the new value and filter the previous matches.
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
	private Button resetButton;
	private Button writeButton;
	private Button lockButton;
	private Button unlockButton;
	private TextView typeLabel;
	private LinearLayout typeList;
	private int selectedType = MemoryTrainer.TYPE_DWORD;
	private ListView listView;
	private ArrayAdapter<String> adapter;

	private final List<MemoryTrainer.Hit> results = new ArrayList<>();
	/**
	 * Type selected when the current results were produced, -1 when there are none.
	 */
	private int searchType = -1;
	/**
	 * Patterns of the current search, keyed by type.
	 */
	private Map<Integer, byte[]> searchPatterns = Collections.emptyMap();
	private Thread worker;
	private volatile boolean cancelled;
	private boolean focusable;
	private boolean busy;

	private final List<MemoryTrainer.Hit> locked = new ArrayList<>();
	private Map<Integer, byte[]> lockedPatterns = Collections.emptyMap();
	private final Handler handler = new Handler(Looper.getMainLooper());

	/**
	 * The result rows show the value currently stored at the address, exactly like the
	 * address list of Cheat Engine, so they are re-read while the panel is open.
	 */
	private final Runnable refreshTask = new Runnable() {
		@Override
		public void run() {
			if (panel != null && panel.getVisibility() == View.VISIBLE
					&& !busy && !results.isEmpty()) {
				refreshValues();
			}
			handler.postDelayed(this, 1000);
		}
	};

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
		handler.postDelayed(refreshTask, 1000);
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
		handler.removeCallbacks(refreshTask);
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
						| WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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
		typeLabel = root.findViewById(R.id.trainer_type_label);
		typeList = root.findViewById(R.id.trainer_type_list);
		searchButton = root.findViewById(R.id.trainer_search);
		resetButton = root.findViewById(R.id.trainer_reset);
		writeButton = root.findViewById(R.id.trainer_write);
		lockButton = root.findViewById(R.id.trainer_lock);
		unlockButton = root.findViewById(R.id.trainer_unlock);

		typeLabel.setOnClickListener(v -> {
			boolean show = typeList.getVisibility() != View.VISIBLE;
			typeList.setVisibility(show ? View.VISIBLE : View.GONE);
			root.requestLayout();
		});
		buildTypeOptions(themed);
		typeLabel.setText(MemoryTrainer.TYPE_LABELS[selectedType] + "  ▾");

		adapter = new ArrayAdapter<String>(themed, android.R.layout.simple_list_item_1) {
			@Override
			public View getView(int position, View convertView, android.view.ViewGroup parent) {
				TextView view = (TextView) super.getView(position, convertView, parent);
				view.setTextSize(12.5f);
				view.setSingleLine(true);
				view.setEllipsize(TextUtils.TruncateAt.END);
				view.setPadding(view.getPaddingLeft(), dp(4), view.getPaddingRight(), dp(4));
				return view;
			}
		};
		listView.setAdapter(adapter);
		listView.setOnItemClickListener(this);

		searchButton.setOnClickListener(this);
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

	/**
	 * The type picker is a plain list inside the panel: a Spinner dropdown is a popup
	 * window and can not be opened from a non focusable overlay.
	 */
	private void buildTypeOptions(Context themed) {
		typeList.removeAllViews();
		for (int i = 0; i < MemoryTrainer.TYPE_LABELS.length; i++) {
			final int type = i;
			TextView option = new TextView(themed);
			option.setText(MemoryTrainer.TYPE_LABELS[i]);
			option.setTextSize(13);
			option.setTextColor(0xFFFFFFFF);
			option.setPadding(dp(8), dp(8), dp(8), dp(8));
			option.setBackgroundColor(0x00000000);
			option.setOnClickListener(v -> selectType(type));
			typeList.addView(option);
		}
	}

	private int dp(int value) {
		return Math.round(getResources().getDisplayMetrics().density * value);
	}

	private void selectType(int type) {
		selectedType = type;
		typeLabel.setText(MemoryTrainer.TYPE_LABELS[type] + "  ▾");
		typeList.setVisibility(View.GONE);
		root.requestLayout();
		if (!results.isEmpty() && type != searchType) {
			resetSearch(getString(R.string.trainer_type_changed));
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
			searchOrRefine();
		} else if (id == R.id.trainer_reset) {
			resetSearch(getString(R.string.trainer_no_result));
		} else if (id == R.id.trainer_write) {
			setWindowFocusable(false);
			writeValues();
		} else if (id == R.id.trainer_lock) {
			startLock();
		} else if (id == R.id.trainer_unlock) {
			unlock();
		} else if (id == R.id.trainer_close) {
			stopSelf();
		} else if (id == R.id.trainer_collapse) {
			setCollapsed(true);
		} else if (id == R.id.trainer_ball) {
			setCollapsed(false);
		}
	}

	private void setCollapsed(boolean collapse) {
		panel.setVisibility(collapse ? View.GONE : View.VISIBLE);
		ball.setVisibility(collapse ? View.VISIBLE : View.GONE);
		setWindowFocusable(false);
		// the window is wrap_content, the layout has to be measured again after the swap
		root.requestLayout();
		if (!collapse && !results.isEmpty()) {
			// the game state may have changed while the panel was collapsed
			refreshValues();
		}
		try {
			windowManager.updateViewLayout(root, params);
		} catch (Exception ignored) {
		}
	}

	@Override
	public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
		if (position < 0 || position >= results.size()) return;
		String value = MemoryTrainer.readHit(results.get(position));
		if (value != null) {
			newValueInput.setText(value);
		}
	}

	/**
	 * The first tap searches the whole memory, every following tap with the same type
	 * filters the previous matches using the value currently entered.
	 */
	private void searchOrRefine() {
		if (worker != null && worker.isAlive()) {
			Toast.makeText(this, R.string.trainer_busy, Toast.LENGTH_SHORT).show();
			return;
		}
		final int selected = selectedType;
		final String text = valueInput.getText().toString();
		final Map<Integer, byte[]> patterns;
		try {
			patterns = buildPatterns(selected, text);
		} catch (NumberFormatException e) {
			setStatus(getString(R.string.trainer_bad_value));
			return;
		}
		if (patterns.isEmpty()) {
			setStatus(getString(R.string.trainer_bad_value));
			return;
		}
		final boolean refine = !results.isEmpty() && selected == searchType;
		// the previous hits must be captured before the list is reset
		final List<MemoryTrainer.Hit> previous = refine
				? new ArrayList<>(results) : Collections.emptyList();
		results.clear();
		searchType = selected;
		searchPatterns = patterns;
		if (!refine) {
			locked.clear();
			lockedPatterns = Collections.emptyMap();
			unlockButton.setEnabled(false);
		}
		setBusy(true);
		setStatus(refine ? getString(R.string.trainer_refining) : getString(R.string.trainer_searching));
		cancelled = false;
		worker = new Thread(() -> {
			List<MemoryTrainer.Hit> found;
			if (refine) {
				found = MemoryTrainer.refineAll(searchPatterns, previous);
			} else if (selected == MemoryTrainer.TYPE_ALL) {
				found = MemoryTrainer.searchAll(searchPatterns, progressListener());
			} else {
				List<Long> addresses = MemoryTrainer.search(selected,
						searchPatterns.get(selected), progressListener());
				found = new ArrayList<>(addresses.size());
				for (Long address : addresses) {
					found.add(new MemoryTrainer.Hit(address, selected));
				}
			}
			handler.post(() -> {
				results.clear();
				results.addAll(found);
				updateResultList();
			});
		}, "memory-search");
		worker.start();
	}

	private Map<Integer, byte[]> buildPatterns(int type, String text) {
		if (type == MemoryTrainer.TYPE_ALL) {
			return MemoryTrainer.patternAll(text);
		}
		Map<Integer, byte[]> map = new LinkedHashMap<>();
		map.put(type, MemoryTrainer.pattern(type, text));
		return map;
	}

	private MemoryTrainer.Listener progressListener() {
		return new MemoryTrainer.Listener() {
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
		};
	}

	private void resetSearch(String status) {
		results.clear();
		searchType = -1;
		searchPatterns = Collections.emptyMap();
		adapter.clear();
		adapter.notifyDataSetChanged();
		listView.clearChoices();
		newValueInput.setText("");
		unlock();
		setBusy(false);
		setStatus(status);
	}

	private void writeValues() {
		if (results.isEmpty()) return;
		final String text = newValueInput.getText().toString();
		final List<MemoryTrainer.Hit> targets = new ArrayList<>();
		int selected = listView.getCheckedItemPosition();
		if (selected >= 0 && selected < results.size()) {
			targets.add(results.get(selected));
		} else {
			targets.addAll(results.size() > 20000 ? results.subList(0, 20000) : results);
		}
		setStatus(getString(R.string.trainer_writing));
		new Thread(() -> {
			int ok = 0;
			for (MemoryTrainer.Hit hit : targets) {
				if (MemoryTrainer.writeHit(hit, text)) {
					ok++;
				}
			}
			final int written = ok;
			handler.post(() -> {
				setStatus(getString(R.string.trainer_written, written, targets.size()));
				refreshValues();
			});
		}, "memory-write").start();
	}

	private void startLock() {
		if (results.isEmpty()) return;
		Map<Integer, byte[]> patterns;
		try {
			patterns = buildPatterns(selectedType,
					newValueInput.getText().toString());
		} catch (NumberFormatException e) {
			setStatus(getString(R.string.trainer_bad_value));
			return;
		}
		if (patterns.isEmpty()) {
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
		lockedPatterns = patterns;
		unlockButton.setEnabled(true);
		setStatus(getString(R.string.trainer_locking, locked.size()));
		handler.removeCallbacks(lockTask);
		handler.post(lockTask);
	}

	private void unlock() {
		locked.clear();
		lockedPatterns = Collections.emptyMap();
		handler.removeCallbacks(lockTask);
		unlockButton.setEnabled(false);
	}

	private final Runnable lockTask = new Runnable() {
		@Override
		public void run() {
			if (locked.isEmpty() || lockedPatterns.isEmpty()) return;
			for (MemoryTrainer.Hit hit : locked) {
				byte[] pattern = lockedPatterns.get(hit.type);
				if (pattern != null) {
					MemoryTrainer.writeValue(hit.address, hit.type, pattern);
				}
			}
			handler.postDelayed(this, 300);
		}
	};

	private void setBusy(boolean busy) {
		this.busy = busy;
		searchButton.setEnabled(!busy);
		resetButton.setEnabled(!busy && !results.isEmpty());
		writeButton.setEnabled(!busy && !results.isEmpty());
		lockButton.setEnabled(!busy && !results.isEmpty());
		updateSearchButton();
	}

	private void updateSearchButton() {
		searchButton.setText(results.isEmpty()
				? R.string.trainer_search : R.string.trainer_continue);
	}

	private void updateResultList() {
		adapter.clear();
		int rows = Math.min(results.size(), MAX_ROWS);
		List<String> values = MemoryTrainer.readHits(
				results.size() > rows ? results.subList(0, rows) : results);
		for (int i = 0; i < rows; i++) {
			String type = searchType == MemoryTrainer.TYPE_ALL
					? " " + MemoryTrainer.typeTag(results.get(i).type) : "";
			adapter.add("0x" + Long.toHexString(results.get(i).address) + type
					+ "   " + values.get(i));
		}
		adapter.notifyDataSetChanged();
		listView.clearChoices();
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
		int rows = Math.min(results.size(), MAX_ROWS);
		List<String> values = MemoryTrainer.readHits(
				results.size() > rows ? results.subList(0, rows) : results);
		final List<String> lines = new ArrayList<>(rows);
		for (int i = 0; i < rows; i++) {
			String type = searchType == MemoryTrainer.TYPE_ALL
					? " " + MemoryTrainer.typeTag(results.get(i).type) : "";
			lines.add("0x" + Long.toHexString(results.get(i).address) + type
					+ "   " + values.get(i));
		}
		// skip the repaint when nothing changed, a refresh runs every second
		boolean same = lines.size() == adapter.getCount();
		for (int i = 0; same && i < rows; i++) {
			same = lines.get(i).equals(adapter.getItem(i));
		}
		if (same) {
			return;
		}
		int checked = listView.getCheckedItemPosition();
		adapter.clear();
		for (String line : lines) {
			adapter.add(line);
		}
		adapter.notifyDataSetChanged();
		if (checked >= 0) {
			listView.setItemChecked(checked, true);
		}
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
